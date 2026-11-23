<#
.SYNOPSIS
  Libera los M800 de prueba en la BASE DE PRUEBAS antes de cada prueba integral,
  para que la cuenta de pruebas (tecnologia@chaideychaide.com) pueda vincularlos.

.DESCRIPTION
  Corre en la PC del backend de pruebas (Docker). Usa SOLO el contenedor de la
  base de PRUEBAS (smart_chaide_test_mysql_service) - nunca produccion.

  1. Busca, en todas las tablas de la base, las filas que contienen el serial o
     el deviceId de los M800 de prueba en columnas de dispositivo.
  2. Muestra esas filas (modo por defecto: SOLO LECTURA, no borra nada).
  3. Con -Apply borra SOLO las filas de las tablas de vinculo (nombre que
     coincide con -BindTablePattern) que referencian esos equipos, dentro de
     una transaccion. Las tablas maestras de dispositivo NO se tocan.

.EXAMPLE
  # 1) Ver que hay (no borra):
  .\reset-m800-pruebas.ps1 -DbUser root -DbPassword '****' -Database smart_chaide

  # 2) Desvincular:
  .\reset-m800-pruebas.ps1 -DbUser root -DbPassword '****' -Database smart_chaide -Apply
#>
param(
    [string]$Container = "smart_chaide_test_mysql_service",
    [Parameter(Mandatory = $true)][string]$Database,
    [string]$DbUser = "root",
    [Parameter(Mandatory = $true)][string]$DbPassword,
    # M800 de prueba: serial impreso y deviceId interno.
    [string[]]$Serials   = @("M800424600184", "M800424600757"),
    [string[]]$DeviceIds = @("ozgywvwnz29e1"),
    # Tablas de VINCULO usuario<->dispositivo (regex sobre el nombre de la tabla).
    [string]$BindTablePattern = "bind|user_device|device_user|userdevice|relation",
    [switch]$Apply
)

$ErrorActionPreference = "Stop"
if ($Container -notmatch "test") { throw "Por seguridad solo se permite un contenedor de PRUEBAS (nombre con 'test'): $Container" }

function Invoke-Sql([string]$sql) {
    $out = docker exec -i $Container mysql -N -B -u$DbUser "-p$DbPassword" $Database -e $sql 2>&1 |
        Where-Object { $_ -notmatch "Using a password on the command line" }
    if ($LASTEXITCODE -ne 0) { throw "Error MySQL: $out" }
    return $out
}

$values = ($Serials + $DeviceIds | ForEach-Object { "'" + ($_ -replace "'", "") + "'" }) -join ","

# Columnas candidatas: texto con 'device' o 'mac' en el nombre.
$cols = Invoke-Sql @"
SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = '$Database'
  AND DATA_TYPE IN ('varchar','char','text')
  AND (COLUMN_NAME LIKE '%device%' OR COLUMN_NAME LIKE '%mac%');
"@

$hits = @()
foreach ($line in $cols) {
    if (-not $line) { continue }
    $table, $column = $line -split "`t"
    $count = [int](Invoke-Sql "SELECT COUNT(*) FROM ``$table`` WHERE ``$column`` IN ($values);")
    if ($count -gt 0) {
        $isBind = $table -match $BindTablePattern
        $hits += [pscustomobject]@{ Tabla = $table; Columna = $column; Filas = $count; EsVinculo = $isBind }
        Write-Host "`n== $table.$column  ($count fila(s))  vinculo=$isBind" -ForegroundColor Cyan
        Invoke-Sql "SELECT * FROM ``$table`` WHERE ``$column`` IN ($values);" | ForEach-Object { Write-Host "   $_" }
    }
}

if (-not $hits) { Write-Host "`nNo hay filas con esos equipos. Nada que hacer." -ForegroundColor Green; exit 0 }

$toDelete = $hits | Where-Object { $_.EsVinculo }
Write-Host "`nResumen:" -ForegroundColor Yellow
$hits | Format-Table -AutoSize

if (-not $toDelete) {
    Write-Host "Ninguna tabla coincide con -BindTablePattern '$BindTablePattern'." -ForegroundColor Yellow
    Write-Host "Revisa el resumen y vuelve a correr con el nombre real, p.ej. -BindTablePattern '^nombre_tabla$'."
    exit 1
}

if (-not $Apply) {
    Write-Host "`nModo SOLO LECTURA. Se borrarian las filas de: $(($toDelete.Tabla | Sort-Object -Unique) -join ', ')" -ForegroundColor Yellow
    Write-Host "Para desvincular, vuelve a correr con -Apply."
    exit 0
}

$stmts = ($toDelete | ForEach-Object { "DELETE FROM ``$($_.Tabla)`` WHERE ``$($_.Columna)`` IN ($values);" }) -join " "
Invoke-Sql "START TRANSACTION; $stmts COMMIT;" | Out-Null
Write-Host "`nDesvinculado. Filas borradas en: $(($toDelete.Tabla | Sort-Object -Unique) -join ', ')" -ForegroundColor Green
Write-Host "La cuenta de pruebas tecnologia@chaideychaide.com ya puede vincular los M800 desde la app."
