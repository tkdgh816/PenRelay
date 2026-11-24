param(
  [Parameter(Mandatory)][string]$Directory,
  [ValidateRange(1,10)][int]$Repetitions = 10
)
$ErrorActionPreference = 'Stop'
$rows = @(Import-Csv (Join-Path $Directory 'received.tsv') -Delimiter "`t")
$cases = @(Import-Csv (Join-Path $Directory 'checkpoints.csv'))
$last = 0
$results = foreach ($case in $cases) {
  $start = [int]$case.start; $cut = [int]$case.cut; $released = [int]$case.released
  $before = [int]$case.beforeDown; $end = [int]$case.end
  if ($start -ne $last -or !($start -lt $cut -and $cut -le $released -and $released -le $before -and $before -le $end -and $end -le $rows.Count)) {
    throw "수신 로그 구간 오류: $($case.scenario) $($case.run)"
  }
  $last = $end
  $errors = [Collections.Generic.List[string]]::new()
  $active = [Collections.Generic.HashSet[string]]::new()
  $down = @(0,0); $up = @(0,0); $moves = @(0,0)
  $gapContacts = 0; $hoverPath = @(); $canceledUp = $false; $residual = 0
  for ($i=$start; $i -lt $end; $i++) {
    $r = $rows[$i]
    $stroke = [int]($i -ge $before)
    $x = [int]$case.x + 140 * $stroke; $y = [int]$case.y + 68 * $stroke
    $inContact = ([int]$r.flags -band 4) -ne 0
    if ($r.type -ne '3' -or $r.message -notin '249','245','246','247','24A') { $errors.Add("알 수 없는 수신 행 $i") }
    if ($i -ge $cut -and $i -lt $before -and ($inContact -or $r.message -eq '246')) { $gapContacts++ }
    if ($i -ge $cut -and $i -lt $released -and $r.message -notin '247','24A') { $errors.Add("정리 중 추가 입력 $i") }
    switch ($r.message) {
      '246' {
        if ($active.Count -ne 0 -or !$active.Add($r.id) -or !$inContact -or
          [int]$r.x -ne $x -or [int]$r.y -ne $y) { $errors.Add("예상 밖 DOWN $i") }
        $down[$stroke]++
      }
      '245' {
        if ($inContact) {
          if (!$active.Contains($r.id) -or [int]$r.x -ne ($x+24) -or [int]$r.y -ne $y) { $errors.Add("획 밖의 접촉 MOVE $i") }
          $moves[$stroke]++
        } elseif ($i -ge $released -and $i -lt $before) {
          if ($active.Count -ne 0 -or $r.pressure -ne '0' -or [int]$r.y -ne ([int]$case.y+68)) { $errors.Add("호버 중 접촉 또는 위치 오류 $i") }
          $hoverPath += [int]$r.x
        }
      }
      '247' {
        if (!$active.Remove($r.id) -or $inContact -or [int]$r.x -ne ($x+24) -or [int]$r.y -ne $y) { $errors.Add("UP 오류 $i") }
        $up[$stroke]++
        if ($i -ge $cut -and $i -lt $released) { $canceledUp = ([int]$r.flags -band 0x8000) -ne 0 }
      }
    }
    if (($i + 1) -eq $cut -and $active.Count -ne 1) { $errors.Add('중단 시 활성 접촉이 1개가 아님') }
    if (($i + 1) -eq $released) { $residual = $active.Count }
  }
  if ($residual -or $active.Count -or $gapContacts) { $errors.Add('잔류 접촉 또는 새 DOWN 전 접촉') }
  if (($down -join ',') -ne '1,1' -or ($up -join ',') -ne '1,1' -or ($moves -join ',') -ne '1,1') { $errors.Add('두 획의 DOWN/MOVE/UP 개수 불일치') }
  if (($hoverPath -join ',') -ne (([int]$case.x+120),([int]$case.x+140) -join ',')) { $errors.Add('다음 DOWN 전 두 호버 지점 불일치') }
  if ($case.scenario -eq 'cancel' -and !$canceledUp) { $errors.Add('명시 취소 플래그 누락') }
  if (($case.firstPid -ne $case.secondPid) -ne ($case.scenario -eq 'host-exit')) { $errors.Add('호스트 생명주기 불일치') }
  [pscustomobject]@{scenario=$case.scenario;run=$case.run;passed=($errors.Count -eq 0);residual=$residual;
    gapContacts=$gapContacts;canceledUp=$canceledUp;down=($down -join '+');up=($up -join '+');
    finalActive=$active.Count;observation=($errors -join ', ')}
}
if ($last -ne $rows.Count -or $cases.Count -ne 4*$Repetitions) { throw '회차 또는 수신 행 누락' }
foreach ($scenario in 'disconnect','cancel','host-exit','mode-switch') {
  $runs = @($cases | Where-Object scenario -eq $scenario | ForEach-Object { [int]$_.run })
  if (($runs -join ',') -ne ((1..$Repetitions) -join ',')) { throw "$scenario 회차 누락 또는 중복" }
}
$results | Export-Csv (Join-Path $Directory 'results.csv') -NoTypeInformation
$results | Group-Object scenario,passed | Select-Object Name,Count | Format-Table
if ($results | Where-Object { !$_.passed }) { throw '복구 판정 실패. results.csv의 observation을 확인하세요.' }
