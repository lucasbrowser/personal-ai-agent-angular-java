
$ErrorActionPreference = "Stop"

$frontendPath = Join-Path (Get-Location) "frontend"

# Instala o Angular CLI caso ainda não esteja disponível.
if (-not (Get-Command ng -ErrorAction SilentlyContinue)) {
    npm install -g @angular/cli@22

    if ($LASTEXITCODE -ne 0) {
        throw "Falha ao instalar Angular CLI."
    }
}

# Cria a pasta caso não exista.
if (-not (Test-Path $frontendPath)) {
    New-Item -ItemType Directory -Path $frontendPath | Out-Null
}

# Ignora somente arquivos .gitkeep.
$existingFiles = Get-ChildItem $frontendPath -Force |
    Where-Object { $_.Name -ne ".gitkeep" }

if ($existingFiles) {
    throw "A pasta frontend possui arquivos. Verifique antes de gerar o projeto."
}

# Remove o arquivo auxiliar do Git antes da geração.
$gitKeep = Join-Path $frontendPath ".gitkeep"

if (Test-Path $gitKeep) {
    Remove-Item $gitKeep
}

# Gera o projeto Angular.
ng new jarvis-web `
    --directory frontend `
    --routing `
    --style scss `
    --standalone `
    --strict `
    --skip-git `
    --package-manager npm `
    --defaults

if ($LASTEXITCODE -ne 0) {
    throw "Falha ao gerar o projeto Angular."
}

Write-Host "Frontend Angular criado com sucesso." -ForegroundColor Green
Write-Host "Execute: cd frontend; npm start"
