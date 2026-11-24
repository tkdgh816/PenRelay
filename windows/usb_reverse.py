"""승인된 단일 USB 태블릿의 세션·등록 포트를 유지한다. Ctrl+C로 종료."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import time


def resolve_adb(selected=None):
  if selected:
    if Path(selected).is_file():
      return Path(selected).resolve()
    command = str(selected)
  elif os.environ.get("ANDROID_HOME"):
    return (Path(os.environ["ANDROID_HOME"]) / "platform-tools" / "adb.exe").resolve(strict=True)
  else:
    command = "adb"
  executable = shutil.which(command)
  if not executable:
    raise FileNotFoundError(f"{command} 실행 파일을 찾을 수 없습니다. ANDROID_HOME 또는 --adb 경로를 확인하세요.")
  return Path(executable)


class UsbReverse:
  def __init__(self, adb, session, pair):
    if session == pair or not all(0 < p < 65536 for p in (session, pair)):
      raise ValueError("서로 다른 세션·등록 포트가 필요합니다.")
    self.adb = str(adb)
    self.ports = [f"tcp:{p}" for p in (session, pair)]
    self.serial = self.call("-d", "get-serialno")
    self.owned = set()

  def call(self, *args):
    return subprocess.run([self.adb, *args], capture_output=True, text=True,
                          check=True, timeout=5).stdout.strip()

  def mappings(self):
    rows = self.call("-s", self.serial, "reverse", "--list").splitlines()
    return dict(row.split()[-2:] for row in rows if row.strip())

  def ensure(self):
    if self.call("-d", "get-serialno") != self.serial:
      raise ConnectionError("처음 선택한 USB 태블릿을 기다리는 중")
    mappings = self.mappings()
    for port in self.ports:
      if port in mappings:
        if mappings[port] != port:
          raise ValueError(f"다른 ADB reverse가 {port} 사용 중")
      else:
        self.call("-s", self.serial, "reverse", "--no-rebind", port, port)
        self.owned.add(port)

  def close(self):
    if self.owned:
      try:
        if self.call("-d", "get-serialno") != self.serial:
          return
        mappings = self.mappings()
        for port in self.owned:
          if mappings.get(port) == port:
            self.call("-s", self.serial, "reverse", "--remove", port)
      except (subprocess.SubprocessError, OSError):
        pass  # 분리된 기기의 reverse는 ADB 전송 종료 시 사라진다.


def main():
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("--adb", type=Path)
  parser.add_argument("--session", type=int, default=47120)
  parser.add_argument("--pair", type=int, default=47121)
  args = parser.parse_args()
  bridge = UsbReverse(resolve_adb(args.adb), args.session, args.pair)
  ready = None
  try:
    while True:
      try:
        bridge.ensure()
        connected = True
      except (subprocess.SubprocessError, ConnectionError):
        connected = False
      if connected != ready:
        print("USB 포트 준비" if connected else "USB 재연결 대기", flush=True)
        ready = connected
      time.sleep(1)
  except KeyboardInterrupt:
    pass
  finally:
    bridge.close()


if __name__ == "__main__":
  main()
