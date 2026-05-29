param(
  [int]$Port = 8787,
  [string]$ArkApiKey = $env:ARK_API_KEY,
  [string]$ArkModel = $env:ARK_MODEL,
  [string]$ArkBaseUrl = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
)

if (-not $ArkApiKey) {
  Write-Error "Missing ARK_API_KEY. Example: `$env:ARK_API_KEY='your-key'; `$env:ARK_MODEL='ep-xxx'; .\ark-proxy.ps1"
  exit 1
}

$listener = [System.Net.HttpListener]::new()
$prefix = "http://127.0.0.1:$Port/"
$listener.Prefixes.Add($prefix)
$listener.Start()

Write-Host "AI proxy listening on ${prefix}api/chat"
Write-Host "Press Ctrl+C to stop."

function Write-JsonResponse {
  param(
    [System.Net.HttpListenerResponse]$Response,
    [int]$StatusCode,
    [string]$Json
  )

  $bytes = [System.Text.Encoding]::UTF8.GetBytes($Json)
  $Response.StatusCode = $StatusCode
  $Response.ContentType = "application/json; charset=utf-8"
  $Response.ContentLength64 = $bytes.Length
  $Response.Headers.Set("Access-Control-Allow-Origin", "*")
  $Response.Headers.Set("Access-Control-Allow-Headers", "content-type")
  $Response.Headers.Set("Access-Control-Allow-Methods", "POST, OPTIONS")
  $Response.OutputStream.Write($bytes, 0, $bytes.Length)
  $Response.OutputStream.Close()
}

function Write-EmptyResponse {
  param(
    [System.Net.HttpListenerResponse]$Response,
    [int]$StatusCode
  )

  $Response.StatusCode = $StatusCode
  $Response.ContentLength64 = 0
  $Response.Headers.Set("Access-Control-Allow-Origin", "*")
  $Response.Headers.Set("Access-Control-Allow-Headers", "content-type")
  $Response.Headers.Set("Access-Control-Allow-Methods", "POST, OPTIONS")
  $Response.OutputStream.Close()
}

try {
  while ($listener.IsListening) {
    $context = $listener.GetContext()
    $request = $context.Request
    $response = $context.Response

    if ($request.HttpMethod -eq "OPTIONS") {
      Write-EmptyResponse -Response $response -StatusCode 204
      continue
    }

    if ($request.Url.AbsolutePath -ne "/api/chat" -or $request.HttpMethod -ne "POST") {
      Write-JsonResponse -Response $response -StatusCode 404 -Json '{"error":"not found"}'
      continue
    }

    try {
      $reader = [System.IO.StreamReader]::new($request.InputStream, [System.Text.Encoding]::UTF8)
      $rawBody = $reader.ReadToEnd()
      $reader.Close()

      $inputData = $rawBody | ConvertFrom-Json
      $model = if ($inputData.model) { [string]$inputData.model } else { $ArkModel }
      if (-not $model) {
        Write-JsonResponse -Response $response -StatusCode 400 -Json '{"error":"missing model"}'
        continue
      }

      $payload = @{
        model = $model
        messages = $inputData.messages
        temperature = 0.35
        max_tokens = 2400
      } | ConvertTo-Json -Depth 20

      $arkResponse = Invoke-RestMethod `
        -Uri $ArkBaseUrl `
        -Method Post `
        -ContentType "application/json" `
        -Headers @{ Authorization = "Bearer $ArkApiKey" } `
        -Body $payload

      $content = $arkResponse.choices[0].message.content
      $json = @{ content = $content; raw = $arkResponse } | ConvertTo-Json -Depth 30
      Write-JsonResponse -Response $response -StatusCode 200 -Json $json
    } catch {
      $message = $_.Exception.Message.Replace('"', '\"')
      Write-JsonResponse -Response $response -StatusCode 500 -Json "{`"error`":`"$message`"}"
    }
  }
} finally {
  if ($listener.IsListening) {
    $listener.Stop()
  }
  $listener.Close()
}
