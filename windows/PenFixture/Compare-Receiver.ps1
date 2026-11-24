param(
  [Parameter(Mandatory)][string]$Sent,
  [Parameter(Mandatory)][string]$Received
)
$ErrorActionPreference = 'Stop'
$sentRows = @(Import-Csv -LiteralPath $Sent -Delimiter "`t")
$receivedRows = @(Import-Csv -LiteralPath $Received -Delimiter "`t")
$messages = @{ hover = '245'; down = '246'; move = '245'; up = '247'; leave = '245' }
$pressures = @(128, 512, 896)
$index = 0
$sample = 0
foreach ($pressure in $pressures) {
  $pointerId = $receivedRows[$index].id
  foreach ($event in (@('hover', 'down') + (@('move') * 30) + @('up', 'leave'))) {
    $expected = $sentRows[$sample]
    $contact = $event -in 'down', 'move'
    $expectedPressure = if ($contact) { $pressure } else { 0 }
    if ($expected.event -ne $event -or [int]$expected.pressure -ne $expectedPressure) {
      throw "송신 표본 $sample 순서 또는 필압 오류"
    }
    $sequence = @($messages[$event])
    if ($event -eq 'hover') { $sequence = @('249', '245') }
    if ($event -eq 'leave') { $sequence = @('245', '24A') }
    foreach ($message in $sequence) {
      $actual = $receivedRows[$index]
      if ($actual.message -ne $message -or $actual.type -ne '3' -or $actual.id -ne $pointerId -or
        $expected.x -ne $actual.x -or $expected.y -ne $actual.y -or
        $expected.x -ne $actual.rawX -or $expected.y -ne $actual.rawY -or
        $expected.pressure -ne $actual.pressure -or $expected.tiltX -ne $actual.tiltX -or
        $expected.tiltY -ne $actual.tiltY -or $actual.penFlags -ne '0' -or
        ([int]$actual.mask -band 13) -ne 13 -or
        ([int]$actual.flags -band 6) -ne ([int]$expected.flags -band 6)) {
        throw "수신 행 $index 불일치 (송신 표본 $sample, 기대 메시지 $message)"
      }
      $index++
    }
    $sample++
  }
}
if ($sentRows.Count -ne $sample -or $receivedRows.Count -ne $index) { throw '로그 표본 수 불일치' }
"통과 · 송신 $sample 표본, 수신 $index 행, 세 획 좌표·필압·유효 기울기와 접촉 경계 일치"
