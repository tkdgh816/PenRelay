using System.Buffers.Binary;
using System.Globalization;
using System.Numerics;
using System.Text;
using System.Xml;

namespace PenRelay.Core;

public readonly record struct InkSample(Vector2 Position, float Pressure);
public enum InkTexture : byte { Smooth, Ballpoint, Felt, Graphite }
public sealed record InkStroke(byte Brush, uint Color, float Width, bool Pressure, InkSample[] Points,
  InkTexture Texture = InkTexture.Smooth)
{
  public float TileSize => Math.Clamp(Width * 12f, 3f, 32f);
  public static Stream OpenTile(InkTexture texture) => typeof(InkStroke).Assembly.GetManifestResourceStream(
    $"brushes/{texture.ToString().ToLowerInvariant()}.png") ?? throw new InvalidDataException("질감 리소스 오류");
  public byte Alpha => Brush switch { 1 => 156, 2 => 80, _ => 255 };
  public static float EffectivePressure(float pressure, int strength) =>
    strength == 100 ? pressure : .5f + (pressure - .5f) * (strength / 100f);
  private float Radius(InkSample p) => Width * (Pressure ? .25f + 1.5f*p.Pressure : 1f) *
    (Brush switch { 1 => .32f, 2 => 1.5f, _ => .5f });

  // Android에서 사용하는 필압 원과 인접 표본 사이의 사각형을 같은 방향으로 채운다.
  public void Outline(Action<Vector2,float> circle, Action<Vector2,Vector2,Vector2,Vector2> quad,
    int start = 0, int end = int.MaxValue)
  {
    for (int i = start; i < Math.Min(end,Points.Length); i++)
    {
      var b = Points[i]; float rb = Radius(b);
      circle(b.Position,rb);
      if (i == 0) continue;
      var a = Points[i-1]; var delta = b.Position-a.Position; var length = delta.Length();
      if (length <= .001f) continue;
      var n = new Vector2(-delta.Y,delta.X)/length;
      float ra = Radius(a);
      quad(a.Position+n*ra,a.Position-n*ra,b.Position-n*rb,b.Position+n*rb);
    }
  }
}

public sealed record InkPage(int Width, int Height, InkStroke[] Strokes)
{
  private static InvalidDataException Invalid() => new("벡터 페이지 형식 또는 좌표 오류");
  public static InkPage Read(string file, PageVersion page) => Parse(File.ReadAllBytes(file),page,true)!;
  public static void Validate(string file, PageVersion page) => Parse(File.ReadAllBytes(file),page,false);
  internal static InkStroke? DecodeStroke(byte[] bytes, PageVersion page, bool retain) =>
    Parse(bytes, page, retain, single: true)?.Strokes[0];

  private static InkPage? Parse(byte[] bytes, PageVersion page, bool retain, bool single = false)
  {
    int offset = 0;
    int Number() {
      if (bytes.Length-offset < 4) throw Invalid();
      int value = BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(offset,4)); offset += 4; return value;
    }
    byte Byte() => offset < bytes.Length ? bytes[offset++] : throw Invalid();
    float Scalar() {
      float v = BitConverter.Int32BitsToSingle(Number());
      return float.IsFinite(v) ? v : throw Invalid();
    }
    if (!single && (Number() != (page.Textured ? 0x494e4b32 : 0x494e4b31) || Number() != page.Width || Number() != page.Height)) throw Invalid();
    int strength = single ? Byte() : 100;
    if (strength > 100) throw Invalid();
    int count = single ? 1 : Number();
    if (count < 0 || count > (bytes.Length-offset)/26) throw Invalid();
    var strokes = retain ? new InkStroke[count] : null;
    for (int i=0;i<count;i++)
    {
      byte brush = Byte(); uint color = unchecked((uint)Number()); float width = Scalar();
      byte pressure = Byte();
      var texture = single || page.Textured ? (InkTexture)Byte() : InkTexture.Smooth;
      if (texture != InkTexture.Smooth && !(brush == 0 && texture is InkTexture.Ballpoint or InkTexture.Felt)
        && !(brush == 1 && texture == InkTexture.Graphite)) throw Invalid();
      int n = Number();
      if (brush > 3 || pressure > 1 || width <= 0 || width > 32768 || n < 1 || n > (bytes.Length-offset)/12) throw Invalid();
      var points = retain ? new InkSample[n] : null;
      for (int j=0;j<n;j++)
      {
        float x = Scalar(), y = Scalar(), p = Scalar();
        if (x < -.01f || x > page.Width+.01f || y < -.01f || y > page.Height+.01f || p is < 0 or > 1) throw Invalid();
        if (points != null) points[j] = new(new(x,y),InkStroke.EffectivePressure(p, strength));
      }
      if (strokes != null) strokes[i] = new(brush,color,width,pressure == 1,points!,texture);
    }
    if (offset != bytes.Length) throw Invalid();
    return retain ? new(page.Width,page.Height,strokes!) : null;
  }

  public void WriteSvg(Stream target)
  {
    using var xml = XmlWriter.Create(target,new XmlWriterSettings { Encoding = new UTF8Encoding(false) });
    const string ns = "http://www.w3.org/2000/svg";
    void Start(string name) => xml.WriteStartElement(name,ns);
    static string F(float n) => n.ToString("R",CultureInfo.InvariantCulture);
    static string P(Vector2 p) => F(p.X)+" "+F(p.Y);
    static string MaskId(InkStroke s) => $"t{(byte)s.Texture}w{BitConverter.SingleToInt32Bits(s.TileSize)}";
    void Rect(string color) {
      Start("rect"); xml.WriteAttributeString("width","100%"); xml.WriteAttributeString("height","100%");
      xml.WriteAttributeString("fill",color); xml.WriteEndElement();
    }
    void Path(InkStroke stroke,string fill) {
      var path = new StringBuilder();
      stroke.Outline((p,r) => {
        string radius = F(r)+" "+F(r);
        path.Append('M').Append(P(p-new Vector2(r,0))).Append('A').Append(radius).Append(" 0 1 1 ")
          .Append(P(p+new Vector2(r,0))).Append('A').Append(radius).Append(" 0 1 1 ")
          .Append(P(p-new Vector2(r,0))).Append('Z');
      }, (a,b,c,d) => path.Append('M').Append(P(a)).Append('L').Append(P(b)).Append('L').Append(P(c)).Append('L').Append(P(d)).Append('Z'));
      Start("path"); xml.WriteAttributeString("fill",fill);
      if (stroke.Texture != InkTexture.Smooth) xml.WriteAttributeString("mask",$"url(#{MaskId(stroke)})");
      xml.WriteAttributeString("opacity",F(stroke.Alpha/255f)); xml.WriteAttributeString("d",path.ToString()); xml.WriteEndElement();
      if (stroke.Texture == InkTexture.Felt) Path(stroke with { Width = stroke.Width * .76f, Texture = InkTexture.Smooth },fill);
    }
    Start("svg"); xml.WriteAttributeString("width",F(Width)); xml.WriteAttributeString("height",F(Height));
    xml.WriteAttributeString("viewBox",$"0 0 {Width} {Height}"); Rect("white");
    var erasers = Enumerable.Range(0,Strokes.Length).Where(i => Strokes[i].Brush == 3).ToArray();
    Start("defs");
    foreach (var s in Strokes.Where(s => s.Texture != InkTexture.Smooth).DistinctBy(MaskId)) {
      string id = MaskId(s), size = F(s.TileSize);
      Start("pattern"); xml.WriteAttributeString("id","p"+id); xml.WriteAttributeString("patternUnits","userSpaceOnUse");
      xml.WriteAttributeString("width",size); xml.WriteAttributeString("height",size);
      Start("image"); xml.WriteAttributeString("width",size); xml.WriteAttributeString("height",size);
      using var tile = InkStroke.OpenTile(s.Texture);
      using var bytes = new MemoryStream(); tile.CopyTo(bytes);
      xml.WriteAttributeString("xlink","href","http://www.w3.org/1999/xlink","data:image/png;base64,"+Convert.ToBase64String(bytes.ToArray()));
      xml.WriteEndElement(); xml.WriteEndElement();
      Start("mask"); xml.WriteAttributeString("id",id); xml.WriteAttributeString("maskUnits","userSpaceOnUse");
      xml.WriteAttributeString("x","0"); xml.WriteAttributeString("y","0");
      xml.WriteAttributeString("width",F(Width)); xml.WriteAttributeString("height",F(Height));
      Rect($"url(#p{id})"); xml.WriteEndElement();
    }
    foreach (int i in erasers) {
      Start("mask"); xml.WriteAttributeString("id",$"e{i}"); xml.WriteAttributeString("maskUnits","userSpaceOnUse");
      xml.WriteAttributeString("x","0"); xml.WriteAttributeString("y","0");
      xml.WriteAttributeString("width",F(Width)); xml.WriteAttributeString("height",F(Height));
      Rect("white"); Path(Strokes[i],"black"); xml.WriteEndElement();
    }
    xml.WriteEndElement();
    foreach (int i in erasers.Reverse()) { Start("g"); xml.WriteAttributeString("mask",$"url(#e{i})"); }
    foreach (var stroke in Strokes) {
      if (stroke.Brush == 3) xml.WriteEndElement(); else Path(stroke,$"#{stroke.Color & 0xffffff:X6}");
    }
    xml.WriteEndElement();
  }
}
