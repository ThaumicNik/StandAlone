$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
$cache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
function Jars($group, $name) { @(Get-ChildItem (Join-Path $cache "$group/$name") -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } | Select-Object -ExpandProperty FullName) }
$stdlib = (Jars 'org.jetbrains.kotlin' 'kotlin-stdlib' | Select-Object -First 1)
$annotations = (Jars 'org.jetbrains' 'annotations' | Select-Object -First 1)
$compiler = @((Jars 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable'), (Jars 'org.jetbrains.kotlin' 'kotlin-stdlib'), (Jars 'org.jetbrains.kotlin' 'kotlin-script-runtime'), (Jars 'org.jetbrains.kotlin' 'kotlin-reflect'), (Jars 'org.jetbrains.intellij.deps' 'trove4j'), (Jars 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm'), $annotations) | ForEach-Object { $_ }
$compilerPath = $compiler -join ';'
$testJars = @((Jars 'org.jetbrains.kotlin' 'kotlin-test'), (Jars 'org.jetbrains.kotlin' 'kotlin-test-junit'), (Jars 'junit' 'junit'), (Jars 'org.hamcrest' 'hamcrest-core')) | ForEach-Object { $_ }
$out = Join-Path $root 'build/manual-check'
New-Item -ItemType Directory -Force "$out/main", "$out/test", "$out/server" | Out-Null
$mainFiles = @(Get-ChildItem sync-core/src/commonMain,sync-core/src/jvmMain -Recurse -Filter '*.kt' | Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } | Select-Object -ExpandProperty FullName)
& java -cp $compilerPath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 11 -classpath "$stdlib;$annotations" -d "$out/main" @mainFiles
if ($LASTEXITCODE) { throw 'Core compilation failed' }
$serverFiles = @(Get-ChildItem sync-server/src/main -Recurse -Filter '*.java' | Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } | Select-Object -ExpandProperty FullName)
& javac -encoding UTF-8 -source 11 -target 11 -cp "$out/main;$stdlib" -d "$out/server" @serverFiles
if ($LASTEXITCODE) { throw 'Server compilation failed' }
$testPath = (@($stdlib, $annotations, "$out/main", "$out/server") + $testJars) -join ';'
$testFiles = @(Get-ChildItem sync-core/src/commonTest,sync-core/src/jvmTest -Recurse -Filter '*.kt' | Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } | Select-Object -ExpandProperty FullName)
& java -cp $compilerPath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 11 -classpath $testPath -d "$out/test" @testFiles
if ($LASTEXITCODE) { throw 'Test compilation failed' }
$classes = $testFiles | ForEach-Object { 'com.edu.standalone.sync.' + [IO.Path]::GetFileNameWithoutExtension($_) }
& java -cp "$testPath;$out/test" org.junit.runner.JUnitCore @classes
if ($LASTEXITCODE) { throw 'Tests failed' }

$javaSmoke = 'sync-server/src/test/java/com/edu/standalone/server/JavaApiSmoke.java'
& javac -encoding UTF-8 -source 11 -target 11 -cp $testPath -d "$out/test" $javaSmoke
if ($LASTEXITCODE) { throw 'Java API compilation failed' }
$smokeFile = Join-Path $out ('java-recovery-' + [guid]::NewGuid().ToString() + '.bin')
try {
    & java -cp "$out/main;$out/test;$stdlib" com.edu.standalone.server.JavaApiSmoke create $smokeFile
    if ($LASTEXITCODE) { throw 'Java API write failed' }
    & java -cp "$out/main;$out/test;$stdlib" com.edu.standalone.server.JavaApiSmoke recover $smokeFile
    if ($LASTEXITCODE) { throw 'Fresh JVM recovery failed' }
} finally {
    Remove-Item -LiteralPath $smokeFile -ErrorAction SilentlyContinue
    $smokeEvents = [IO.Path]::GetFullPath($smokeFile + '.events')
    $checkRoot = [IO.Path]::GetFullPath($out) + [IO.Path]::DirectorySeparatorChar
    if ($smokeEvents.StartsWith($checkRoot, [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $smokeEvents)) {
        Remove-Item -LiteralPath $smokeEvents -Recurse -Force
    }
}

