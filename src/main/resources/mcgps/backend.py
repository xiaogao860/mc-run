"""MC GPS phone worker: one line of JSON in, one line of JSON out.

Adapted from iphone-location-controller (GPL-3.0). Talks to the iPhone through
pymobiledevice3: mount developer image -> userspace RSD tunnel -> DVT
LocationSimulation. Coordinates are GPS / WGS-84.
"""
import asyncio
import json
import math
import os
import re
import sys
import warnings

warnings.filterwarnings('ignore', message='urllib3 v2 only supports')


def emit(event, **fields):
    sys.stdout.write(json.dumps(dict(event=event, **fields), ensure_ascii=False) + '\n')
    sys.stdout.flush()


def validate(lat, lon):
    lat, lon = float(lat), float(lon)
    if not math.isfinite(lat) or not math.isfinite(lon) or abs(lat) > 85 or abs(lon) > 180:
        raise ValueError('坐标超出范围')
    return lat, lon


HINTS = [
    (('not connected', 'nodeviceconnected', 'no device'), '没有检测到 iPhone：请用数据线连接电脑，解锁手机并点“信任”'),
    (('developermode', 'developer mode'), '手机未开启开发者模式：设置 → 隐私与安全性 → 开发者模式'),
    (('passwordrequired', 'passcode', 'device is locked'), '请先解锁手机再连接'),
    (('notpaired', 'pairing', 'userdeniedpairing'), '手机还没有信任这台电脑：解锁手机，在弹窗里点“信任”'),
    (('timeout', 'timed out'), '连接超时：请重新插拔数据线，保持手机解锁后再试'),
]


def friendly(text):
    if isinstance(text, BaseException):
        text = str(text) or type(text).__name__
    lines = [l.strip() for l in str(text).splitlines()
             if l.strip() and 'warnings.warn' not in l and 'Warning:' not in l]
    last = lines[-1] if lines else str(text).strip()
    last = re.sub(r'^\d{4}-\d\d-\d\d [\d:]+ \S+ \S+\[\d+\] [A-Z]+ ', '', last)
    low = last.lower()
    for keys, hint in HINTS:
        if any(k in low for k in keys):
            return hint
    return last[-300:] or '未知错误'


async def prepare():
    options = {'creationflags': 0x08000000} if os.name == 'nt' else {}
    env = dict(os.environ, PYTHONWARNINGS='ignore')
    p = await asyncio.create_subprocess_exec(
        sys.executable, '-m', 'pymobiledevice3', 'mounter', 'auto-mount',
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE, env=env, **options)
    try:
        out, err = await asyncio.wait_for(p.communicate(), 120)
    except BaseException:
        if p.returncode is None:
            p.kill()
            await p.wait()
        raise
    if p.returncode:
        text = (err or out).decode(errors='replace')
        if 'already mounted' not in text.lower():
            raise RuntimeError(friendly(text))


async def session():
    from pymobiledevice3.remote.userspace_tunnel import establish_userspace_rsd
    from pymobiledevice3.services.dvt.instruments.dvt_provider import DvtProvider
    from pymobiledevice3.services.dvt.instruments.location_simulation import LocationSimulation

    emit('status', message='正在准备开发支持组件，请保持手机解锁…')
    await prepare()
    emit('status', message='正在建立 USB 开发连接…')
    rsd = await asyncio.wait_for(establish_userspace_rsd(), 40)
    loop = asyncio.get_running_loop()
    async with DvtProvider(rsd) as dvt, LocationSimulation(dvt) as loc:
        emit('ready')
        try:
            while True:
                line = await loop.run_in_executor(None, sys.stdin.readline)
                if not line:
                    break
                cmd = json.loads(line)
                action = cmd.get('action')
                if action == 'stop':
                    break
                if action != 'set':
                    raise ValueError('未知操作')
                lat, lon = validate(cmd['lat'], cmd['lon'])
                await asyncio.wait_for(loc.set(lat, lon), 8)
                emit('position', lat=lat, lon=lon)
        finally:
            try:
                await asyncio.wait_for(loc.clear(), 8)
                emit('cleared')
            except Exception as exc:
                emit('error', message='恢复真实定位未确认，请重新连接后断开，或重启手机：' + friendly(exc))
                raise


def main():
    try:
        asyncio.run(session())
    except Exception as exc:
        emit('error', message=friendly(str(exc) or type(exc).__name__))
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
