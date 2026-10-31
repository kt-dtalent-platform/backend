# DB 실행 생략 옵션
param([switch]$SkipDatabase)
$ErrorActionPreference = 'Stop'
# 프로젝트 경로
$projectPath = Split-Path $PSScriptRoot -Parent
Push-Location -LiteralPath $projectPath
try {
    # Java 경로
    if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
        $localJdk = Get-ChildItem -LiteralPath '.tools\jdk21' -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($localJdk -and (Test-Path -LiteralPath (Join-Path $localJdk.FullName 'bin\java.exe'))) {
            $env:JAVA_HOME = $localJdk.FullName
        } else { throw 'Java 21을 설치하거나 JAVA_HOME을 설정하세요.' }
    }
    # 환경변수
    if (-not (Test-Path -LiteralPath '.env')) { throw '.env.example을 .env로 복사하고 설정하세요.' }
    foreach ($line in Get-Content -LiteralPath '.env') {
        if ($line.Trim() -eq '' -or $line.TrimStart().StartsWith('#')) { continue }
        $pair = $line.Split('=', 2)
        if ($pair.Length -ne 2 -or $pair[0].Trim() -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') { throw '.env 형식을 확인하세요.' }
        [Environment]::SetEnvironmentVariable($pair[0].Trim(), $pair[1], 'Process')
    }
    # DB 실행
    if (-not $SkipDatabase) {
        docker compose up -d --wait
        if ($LASTEXITCODE -ne 0) { throw 'Docker MySQL 실행에 실패했습니다.' }
    }
    # 서버 실행
    & .\gradlew.bat bootRun
    if ($LASTEXITCODE -ne 0) { throw '백엔드 실행에 실패했습니다.' }
} finally {
    # 작업 경로 복원
    Pop-Location
}
