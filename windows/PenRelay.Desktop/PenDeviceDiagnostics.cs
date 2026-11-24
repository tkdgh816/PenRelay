using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

namespace PenRelay.Desktop;

internal static class PenDeviceDiagnostics
{
  private const uint Pen = 3;
  private const uint FeedbackNone = 3;

  internal static string CheckCreation()
  {
    var handle = CreateSyntheticPointerDevice(Pen, 1, FeedbackNone);
    if (handle == IntPtr.Zero)
    {
      var error = new Win32Exception(Marshal.GetLastWin32Error());
      return $"PT_PEN 장치 생성 실패 ({error.NativeErrorCode}) {error.Message}";
    }

    DestroySyntheticPointerDevice(handle);
    return "PT_PEN 장치 생성 및 해제 성공";
  }

  [DllImport("user32.dll", SetLastError = true, ExactSpelling = true)]
  private static extern IntPtr CreateSyntheticPointerDevice(uint pointerType, uint maxCount, uint feedbackMode);

  [DllImport("user32.dll", ExactSpelling = true)]
  private static extern void DestroySyntheticPointerDevice(IntPtr device);
}
