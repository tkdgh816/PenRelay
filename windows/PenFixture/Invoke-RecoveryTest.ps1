# Run from PowerShell 7 in an interactive Windows desktop.
[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$ReceiverExe,
  [Parameter(Mandatory)][string]$SenderExe,
  [Parameter(Mandatory)][string]$OutputDirectory,
  [ValidateRange(1,10)][int]$Repetitions = 10
)
$ErrorActionPreference = 'Stop'
$senderPath = (Resolve-Path -LiteralPath $SenderExe).Path
$receiverPath = (Resolve-Path -LiteralPath $ReceiverExe).Path
$dir = (New-Item -ItemType Directory -Path $OutputDirectory).FullName
$raw = Join-Path $dir 'received.tsv'
$journal = Join-Path $dir 'host.tsv'
"utc`tcase`trun`tpid`taction`tresult" | Set-Content $journal

function Record($worker, $action, $result) {
  "$([DateTime]::UtcNow.ToString('o'))`t$scenario`t$run`t$($worker.Process.Id)`t$action`t$result" | Add-Content $journal
}
function Read-Reply($worker) {
  $task = $worker.Process.StandardOutput.ReadLineAsync()
  if (!$task.Wait(40000)) { throw '시험 송신기 응답 시간 초과' }
  if ($null -eq $task.Result) { throw "송신기 예기치 않은 종료: $($worker.Process.StandardError.ReadToEnd())" }
  return $task.Result
}
function Start-Worker {
  $pipe = [IO.Pipes.AnonymousPipeServerStream]::new('Out', 'Inheritable')
  $info = [Diagnostics.ProcessStartInfo]::new($senderPath)
  $info.UseShellExecute = $false
  $info.CreateNoWindow = $true
  $info.RedirectStandardInput = $info.RedirectStandardOutput = $info.RedirectStandardError = $true
  foreach ($arg in '--recovery-host', [string]$receiver.Id, $pipe.GetClientHandleAsString()) { $info.ArgumentList.Add($arg) }
  try {
    $process = [Diagnostics.Process]::Start($info)
    $pipe.DisposeLocalCopyOfClientHandle()
    $worker = [pscustomobject]@{ Process=$process; Pipe=$pipe; Input=$process.StandardInput; X=0; Y=0 }
    $ready = Read-Reply $worker
    if ($ready -notmatch '^ready (-?\d+) (-?\d+)$') { throw "시작 실패: $ready" }
    $worker.X = [int]$Matches[1]
    $worker.Y = [int]$Matches[2]
    Record $worker 'start' $ready
    return $worker
  } catch {
    if ($process -and !$process.HasExited) { $process.Kill(); $process.WaitForExit() }
    $pipe.Dispose()
    throw
  }
}
function Send-Command($worker, $command, $expected = 'ok') {
  $worker.Input.WriteLine($command)
  $worker.Input.Flush()
  $reply = Read-Reply $worker
  Record $worker $command $reply
  if ($reply -ne $expected) { throw "시험 명령 $command 응답 $reply (기대 $expected)" }
}
function Stop-Worker($worker, [bool]$abrupt) {
  try {
    if (!$worker.Process.HasExited) {
      if ($abrupt) { $worker.Process.Kill() }
      else { $worker.Input.WriteLine('quit'); $worker.Input.Flush() }
      if (!$worker.Process.WaitForExit(5000)) { throw '송신기 종료 시간 초과' }
      Record $worker $(if ($abrupt) { 'kill' } else { 'quit' }) $worker.Process.ExitCode
      if (!$abrupt -and $worker.Process.ExitCode -ne 0) { throw $worker.Process.StandardError.ReadToEnd() }
    }
  } finally {
    if (!$worker.Process.HasExited) { $worker.Process.Kill(); $worker.Process.WaitForExit() }
    $worker.Input.Dispose()
    $worker.Pipe.Dispose()
    $worker.Process.Dispose()
  }
}
function Read-ReceiverLines {
  $stream = [IO.File]::Open($raw, 'Open', 'Read', 'ReadWrite')
  $reader = [IO.StreamReader]::new($stream)
  try { return ,($reader.ReadToEnd().Split([char]10, [StringSplitOptions]::RemoveEmptyEntries)) }
  finally { $reader.Dispose() }
}
function RowCount { return ((Read-ReceiverLines).Length - 1) }
function Wait-ForContact($worker, $start) {
  $timeout = [Diagnostics.Stopwatch]::StartNew()
  do {
    $lines = Read-ReceiverLines
    for ($i=$start+1; $i -lt $lines.Length; $i++) {
      $row = $lines[$i].Split("`t")
      if ($row[0] -eq '245' -and [int]$row[3] -eq $worker.X + 24 -and [int]$row[4] -eq $worker.Y -and
        ([int]$row[12] -band 4)) { return ($lines.Length - 1) }
    }
    Start-Sleep -Milliseconds 20
  } while ($timeout.ElapsedMilliseconds -lt 2000)
  throw '중단 직전 수신 창에서 접촉 MOVE를 관찰하지 못했습니다.'
}

$receiver = Start-Process $receiverPath -ArgumentList "--p0-receive `"$raw`"" -PassThru
try {
  $null = $receiver.WaitForInputIdle(10000)
  $readyTimer = [Diagnostics.Stopwatch]::StartNew()
  while (!(Test-Path -LiteralPath $raw) -or !$receiver.MainWindowHandle) {
    if ($receiver.HasExited -or $readyTimer.ElapsedMilliseconds -gt 10000) { throw '수신 창 준비 실패' }
    Start-Sleep -Milliseconds 50
    $receiver.Refresh()
  }
  $end = 0
  foreach ($scenario in 'disconnect','cancel','host-exit','mode-switch') {
    for ($run=1; $run -le $Repetitions; $run++) {
      $worker = Start-Worker
      try {
        $x = $worker.X; $y = $worker.Y
        $firstPid = $worker.Process.Id
        $start = $end
        Send-Command $worker 'first'
        $cut = Wait-ForContact $worker $start
        switch ($scenario) {
          'disconnect' {
            $worker.Input.Close()
            $reply = Read-Reply $worker
            Record $worker 'EOF' $reply
            if ($reply -ne 'disconnected') { throw 'EOF 미관찰' }
            $worker.Input = [IO.StreamWriter]::new($worker.Pipe, [Text.UTF8Encoding]::new($false))
          }
          'cancel' { Send-Command $worker 'cancel' }
          'host-exit' {
            $dead = $worker; $worker = $null
            Stop-Worker $dead $true
          }
          'mode-switch' {
            Send-Command $worker 'canvas'
            Send-Command $worker 'late-move' 'blocked'
            Send-Command $worker 'first' 'blocked'
          }
        }
        Start-Sleep -Milliseconds 250
        $released = RowCount
        if ($scenario -eq 'host-exit') { $worker = Start-Worker }
        if ($scenario -eq 'mode-switch') { Send-Command $worker 'digitizer' }
        if ($worker.X -ne $x -or $worker.Y -ne $y) { throw '시험면 이동' }
        Send-Command $worker 'late-move' 'blocked'
        Send-Command $worker 'hover'
        Start-Sleep -Milliseconds 250
        $beforeDown = RowCount
        Send-Command $worker 'second'
        Send-Command $worker 'up'
        $secondPid = $worker.Process.Id
      } finally {
        if ($worker) { Stop-Worker $worker $false }
      }
      Start-Sleep -Milliseconds 100
      $end = RowCount
      [pscustomobject]@{scenario=$scenario;run=$run;x=$x;y=$y;firstPid=$firstPid;secondPid=$secondPid;
        start=$start;cut=$cut;released=$released;beforeDown=$beforeDown;end=$end} |
        Export-Csv (Join-Path $dir 'checkpoints.csv') -Append -NoTypeInformation
    }
    Write-Output "$scenario ${Repetitions}회 실행"
  }
} finally {
  if (!$receiver.HasExited) { $null = $receiver.CloseMainWindow(); $null = $receiver.WaitForExit(5000) }
}
& "$PSScriptRoot/Compare-Recovery.ps1" -Directory $dir -Repetitions $Repetitions
