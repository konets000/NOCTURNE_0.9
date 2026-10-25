# NOCTURNE local-only Windows launcher (PowerShell 5.1+, Windows 10/11).
# Serves only loopback requests and a strict file allowlist. No external server or runtime install is used.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Port = 18473
$BaseUrl = "http://127.0.0.1:$Port/"
$Root = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$DictionaryRoot = Join-Path $Root 'dictionary'
$requiredFiles = @(
    'index.html',
    'app.js',
    'dictionary/cc-cedict.txt.gz',
    'dictionary/unihan-characters.json.gz',
    'dictionary/segmentation.txt.gz',
    'dictionary/manifest.json',
    'dictionary/NOTICE.txt'
)
foreach ($relative in $requiredFiles) {
    if (-not (Test-Path -LiteralPath (Join-Path $Root $relative) -PathType Leaf)) {
        Write-Host "NOCTURNE package is incomplete: missing $relative" -ForegroundColor Red
        Write-Host 'Re-download and extract the complete Windows portable package.'
        exit 2
    }
}

function Read-GzipBytes([string]$Path) {
    $inputStream = [System.IO.File]::OpenRead($Path)
    $gzipStream = $null
    $memoryStream = New-Object System.IO.MemoryStream
    try {
        $gzipStream = [System.IO.Compression.GZipStream]::new(
            $inputStream, [System.IO.Compression.CompressionMode]::Decompress)
        $gzipStream.CopyTo($memoryStream)
        $bytes = $memoryStream.ToArray()
        return ,$bytes
    }
    finally {
        if ($null -ne $gzipStream) { $gzipStream.Dispose() }
        $memoryStream.Dispose()
        $inputStream.Dispose()
    }
}

function Send-HttpResponse(
    [System.IO.Stream]$Stream,
    [int]$Status,
    [string]$Reason,
    [string]$ContentType,
    [byte[]]$Body,
    [bool]$HeadOnly
) {
    $csp = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; font-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'"
    $headerText = "HTTP/1.1 $Status $Reason`r`n" +
        "Content-Type: $ContentType`r`n" +
        "Content-Length: $($Body.Length)`r`n" +
        "Cache-Control: no-store`r`n" +
        "X-Content-Type-Options: nosniff`r`n" +
        "Referrer-Policy: no-referrer`r`n" +
        "Content-Security-Policy: $csp`r`n" +
        "Connection: close`r`n`r`n"
    $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($headerText)
    $Stream.Write($headerBytes, 0, $headerBytes.Length)
    if (-not $HeadOnly -and $Body.Length -gt 0) {
        $Stream.Write($Body, 0, $Body.Length)
    }
    $Stream.Flush()
}

function Read-BoundedHttpLine([System.IO.StreamReader]$Reader, [int]$MaxLength) {
    $line = New-Object System.Text.StringBuilder
    while ($true) {
        $code = $Reader.Read()
        if ($code -eq -1) {
            if ($line.Length -eq 0) { return $null }
            return $line.ToString().TrimEnd([char[]]@(13))
        }
        if ($code -eq 10) { return $line.ToString().TrimEnd([char[]]@(13)) }
        if ($line.Length -ge $MaxLength) { throw [System.IO.InvalidDataException]::new('HTTP line exceeds its size limit.') }
        [void]$line.Append([char]$code)
    }
}

function Handle-HttpClient([System.Net.Sockets.TcpClient]$Client) {
    $stream = $Client.GetStream()
    $stream.ReadTimeout = 3000
    $reader = $null
    try {
        $reader = [System.IO.StreamReader]::new(
            $stream, [System.Text.Encoding]::ASCII, $false, 1024, $true)
        $requestLine = Read-BoundedHttpLine $reader 4096
        if ([string]::IsNullOrWhiteSpace($requestLine)) { return }
        $parts = $requestLine -split ' '
        if ($parts.Length -lt 2) { return }
        $method = $parts[0].ToUpperInvariant()
        $requestPath = ($parts[1] -split '\?')[0]

        # Bound both individual lines and aggregate headers; do not accept request bodies.
        $headerCount = 0
        $headerChars = 0
        $headersComplete = $false
        while ($true) {
            $line = Read-BoundedHttpLine $reader 8192
            if ($null -eq $line) { return }
            if ($line.Length -eq 0) { $headersComplete = $true; break }
            $headerCount++
            $headerChars += $line.Length + 2
            if ($headerCount -gt 100 -or $headerChars -gt 16384) { return }
        }
        if (-not $headersComplete) { return }

        if ($method -ne 'GET' -and $method -ne 'HEAD') {
            $empty = [byte[]]@()
            Send-HttpResponse $stream 405 'Method Not Allowed' 'text/plain; charset=utf-8' $empty ($method -eq 'HEAD')
            return
        }

        $contentType = 'text/plain; charset=utf-8'
        [byte[]]$body = @()
        $status = 200
        $reason = 'OK'
        switch -Exact ($requestPath) {
            '/' {
                $body = [System.IO.File]::ReadAllBytes((Join-Path $Root 'index.html'))
                $contentType = 'text/html; charset=utf-8'
                break
            }
            '/index.html' {
                $body = [System.IO.File]::ReadAllBytes((Join-Path $Root 'index.html'))
                $contentType = 'text/html; charset=utf-8'
                break
            }
            '/app.js' {
                $body = [System.IO.File]::ReadAllBytes((Join-Path $Root 'app.js'))
                $contentType = 'application/javascript; charset=utf-8'
                break
            }
            '/dictionary/cc-cedict.txt' {
                $body = Read-GzipBytes (Join-Path $DictionaryRoot 'cc-cedict.txt.gz')
                break
            }
            '/dictionary/unihan-characters.json' {
                $body = Read-GzipBytes (Join-Path $DictionaryRoot 'unihan-characters.json.gz')
                $contentType = 'application/json; charset=utf-8'
                break
            }
            '/dictionary/segmentation.txt' {
                $body = Read-GzipBytes (Join-Path $DictionaryRoot 'segmentation.txt.gz')
                break
            }
            '/dictionary/manifest.json' {
                $body = [System.IO.File]::ReadAllBytes((Join-Path $DictionaryRoot 'manifest.json'))
                $contentType = 'application/json; charset=utf-8'
                break
            }
            '/dictionary/NOTICE.txt' {
                $body = [System.IO.File]::ReadAllBytes((Join-Path $DictionaryRoot 'NOTICE.txt'))
                break
            }
            default {
                $status = 404
                $reason = 'Not Found'
                $body = [System.Text.Encoding]::UTF8.GetBytes('Not found')
                break
            }
        }
        Send-HttpResponse $stream $status $reason $contentType $body ($method -eq 'HEAD')
    }
    catch {
        # A disconnected tab or a malformed request should not take down the local reader.
    }
    finally {
        if ($null -ne $reader) { $reader.Dispose() }
        $stream.Dispose()
        $Client.Dispose()
    }
}

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
try {
    $listener.Start()
}
catch {
    Write-Host "NOCTURNE could not reserve local port $Port. Close any existing NOCTURNE window and retry." -ForegroundColor Red
    Write-Host 'No network address outside this computer is used.'
    exit 3
}

$browser = $null
try {
    $edgeCandidates = @(
        "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe",
        "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe",
        "$env:LOCALAPPDATA\Microsoft\Edge\Application\msedge.exe"
    )
    $edge = $edgeCandidates | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) } | Select-Object -First 1
    if ($edge) {
        $profile = Join-Path $env:LOCALAPPDATA 'NOCTURNE\BrowserProfile'
        New-Item -ItemType Directory -Force -Path $profile | Out-Null
        $arguments = @("--app=$BaseUrl", "--user-data-dir=`"$profile`"", '--no-first-run', '--disable-background-mode')
        $browser = Start-Process -FilePath $edge -ArgumentList $arguments -PassThru
    }
    else {
        Write-Host 'Microsoft Edge is required for the portable app window and was not found.' -ForegroundColor Red
        Write-Host 'Install or repair Microsoft Edge, then run NOCTURNE again.' -ForegroundColor Yellow
        exit 4
    }

    Write-Host 'NOCTURNE is running locally. Keep this window open; close the NOCTURNE browser window to stop it.' -ForegroundColor Green
    while ($true) {
        if ($null -ne $browser -and $browser.HasExited) { break }
        if ($listener.Pending()) {
            $client = $listener.AcceptTcpClient()
            Handle-HttpClient $client
        }
        else {
            Start-Sleep -Milliseconds 75
        }
    }
}
finally {
    $listener.Stop()
}
