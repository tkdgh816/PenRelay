using System.Runtime.InteropServices;

namespace PenRelay.Desktop;

[StructLayout(LayoutKind.Sequential)]
internal struct Pixel(int x, int y)
{
  internal int X = x, Y = y;
}

[StructLayout(LayoutKind.Sequential)]
internal struct PointerInfo
{
  internal uint Type, Id, Frame, Flags;
  internal nint Device, Target;
  internal Pixel Position, Himetric, Raw, RawHimetric;
  internal uint Time, History;
  internal int Input;
  internal uint Keys;
  internal ulong Performance;
  internal uint ButtonChange;
}

[StructLayout(LayoutKind.Sequential)]
internal struct PenInfo
{
  internal PointerInfo Pointer;
  internal uint Flags, Mask, Pressure, Rotation;
  internal int TiltX, TiltY;
}

// x64 Windows SDK union also includes POINTER_TOUCH_INFO, its largest member.
[StructLayout(LayoutKind.Explicit, Size = 152)]
internal struct PointerTypeInfo
{
  [FieldOffset(0)] internal uint Type;
  [FieldOffset(8)] internal PenInfo Pen;
}
