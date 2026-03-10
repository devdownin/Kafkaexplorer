# Script d'automatisation de mise à jour pour Windows 11
$repo = "devdownin/Kafkaexplorer"
$apiUrl = "https://api.github.com/repos/$repo/releases/latest"

Write-Host "Recherche de la dernière version sur GitHub..." -ForegroundColor Cyan
try {
    $release = Invoke-RestMethod -Uri $apiUrl
} catch {
    Write-Error "Impossible de récupérer les informations de la release : $($_.Exception.Message)"
    exit 1
}

$tag = $release.tag_name
Write-Host "Version détectée : $tag" -ForegroundColor Green

# Trouver le JAR (exclure l'original si présent)
$asset = $release.assets | Where-Object { $_.name -like "*.jar" -and $_.name -notlike "*original*" } | Select-Object -First 1

if ($null -eq $asset) {
    Write-Error "Aucun fichier JAR trouvé dans la release $tag"
    exit 1
}

Write-Host "Téléchargement de $($asset.name)..." -ForegroundColor Cyan
Invoke-WebRequest -Uri $asset.browser_download_url -OutFile "app.jar"

Write-Host "Déploiement avec Docker Compose..." -ForegroundColor Cyan
docker-compose -f docker-compose.release.yml up --build -d

Write-Host "Mise à jour terminée avec succès !" -ForegroundColor Green
