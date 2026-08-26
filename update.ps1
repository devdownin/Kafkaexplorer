# Script d'automatisation de mise à jour pour Windows 11
#
# Il téléchargeait le JAR de la dernière Release puis le reconstruisait localement en image
# via `Dockerfile.release`, à travers un fichier compose entier (`docker-compose.release.yml`)
# dont c'était l'unique raison d'être. Or la release publie déjà cette image — depuis ce même
# JAR, pour amd64 et arm64, signée sans clé, avec un SBOM et une provenance SLSA derrière.
# La tirer est strictement meilleur que la rebâtir : plus rien à compiler, rien à vérifier
# soi-même, et l'overlay `compose/image.yml` est le chemin que tout le monde emprunte.
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

# `v1.9.1` côté git, `1.9.1` côté registre : les tags d'image n'ont jamais porté le `v`.
$env:EXPLORER_IMAGE_TAG = $tag -replace '^v', ''

# `docker compose`, pas `docker-compose` : la v1 en tiret est en fin de vie et absente des
# installations récentes de Docker Desktop.
$composeArgs = @("-f", "docker-compose.yml", "-f", "compose/image.yml")

Write-Host "Téléchargement de l'image $($env:EXPLORER_IMAGE_TAG)..." -ForegroundColor Cyan
docker compose @composeArgs pull
if ($LASTEXITCODE -ne 0) {
    Write-Error "Le pull a échoué. L'image de la version $tag est-elle publiée ?"
    exit 1
}

Write-Host "Déploiement avec Docker Compose..." -ForegroundColor Cyan
docker compose @composeArgs up -d
if ($LASTEXITCODE -ne 0) {
    Write-Error "Le démarrage a échoué."
    exit 1
}

Write-Host "Mise à jour terminée avec succès !" -ForegroundColor Green
