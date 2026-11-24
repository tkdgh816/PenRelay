using System;
using System.Buffers.Binary;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using PenRelay.Core;

namespace PenRelay.Desktop;

internal static class CanvasClipboard
{
  private const uint CfDib = 8;
  private const uint GmemMoveable = 2;

  public static async Task Copy(nint owner, CanvasImage image)
  {
    var dib = await Task.Run(() => CreateDib(image));
    nint handle = GlobalAlloc(GmemMoveable, (nuint)dib.Length);
    if (handle == 0) throw new Win32Exception();
    try
    {
      nint address = GlobalLock(handle);
      if (address == 0) throw new Win32Exception();
      try { Marshal.Copy(dib, 0, address, dib.Length); }
      finally { GlobalUnlock(handle); }

      for (int attempt = 0; !OpenClipboard(owner); attempt++)
      {
        if (attempt == 8) throw new Win32Exception();
        await Task.Delay(30);
      }
      try
      {
        if (!EmptyClipboard() || SetClipboardData(CfDib, handle) == 0) throw new Win32Exception();
        handle = 0; // 성공하면 Windows가 메모리의 소유권을 갖는다.
      }
      finally { CloseClipboard(); }
    }
    finally { if (handle != 0) GlobalFree(handle); }
  }

  // BITMAPINFOHEADER 다음에 행당 4바이트 경계로 정렬한 24비트 BGR 픽셀을 아래 행부터 저장한다.
  private static byte[] CreateDib(CanvasImage image)
  {
    const int headerSize = 40;
    int stride = checked((image.Width * 3 + 3) & ~3);
    int pixelSize = checked(stride * image.Height);
    var dib = new byte[checked(headerSize + pixelSize)];
    BinaryPrimitives.WriteInt32LittleEndian(dib, headerSize);
    BinaryPrimitives.WriteInt32LittleEndian(dib.AsSpan(4), image.Width);
    BinaryPrimitives.WriteInt32LittleEndian(dib.AsSpan(8), image.Height);
    BinaryPrimitives.WriteUInt16LittleEndian(dib.AsSpan(12), 1);
    BinaryPrimitives.WriteUInt16LittleEndian(dib.AsSpan(14), 24);
    BinaryPrimitives.WriteInt32LittleEndian(dib.AsSpan(20), pixelSize);
    var rgba = image.Rgba;
    for (int y = 0; y < image.Height; y++)
    {
      int source = y * image.Width * 4;
      int dest = headerSize + (image.Height - 1 - y) * stride;
      for (int x = 0; x < image.Width; x++, source += 4, dest += 3)
      {
        int alpha = rgba[source + 3];
        for (int c = 0; c < 3; c++)
          dib[dest + c] = (byte)((rgba[source + 2 - c] * alpha + 255 * (255 - alpha) + 127) / 255);
      }
    }
    return dib;
  }

  [DllImport("user32", SetLastError = true)] private static extern bool OpenClipboard(nint owner);
  [DllImport("user32", SetLastError = true)] private static extern bool EmptyClipboard();
  [DllImport("user32", SetLastError = true)] private static extern nint SetClipboardData(uint format, nint handle);
  [DllImport("user32")] private static extern bool CloseClipboard();
  [DllImport("kernel32", SetLastError = true)] private static extern nint GlobalAlloc(uint flags, nuint size);
  [DllImport("kernel32", SetLastError = true)] private static extern nint GlobalLock(nint handle);
  [DllImport("kernel32")] private static extern bool GlobalUnlock(nint handle);
  [DllImport("kernel32")] private static extern nint GlobalFree(nint handle);
}
