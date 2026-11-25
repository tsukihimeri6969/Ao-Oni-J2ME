@echo off
setlocal

set JDK_HOME=C:\Program Files\Java\jdk1.8.0_202
set WTK_HOME=H:\WTK2.5.2
set PROGUARD=tools\proguard.jar

set BOOTCP=%WTK_HOME%\lib\cldcapi11.jar;%WTK_HOME%\lib\midpapi20.jar

if exist build rmdir /s /q build
if exist dist rmdir /s /q dist
mkdir build\classes
mkdir dist

echo [1/4] Compiling CLDC 1.1 / MIDP 2.0 classes...
"%JDK_HOME%\bin\javac" -source 1.3 -target 1.1 ^
  -bootclasspath "%BOOTCP%" ^
  -d build\classes src\*.java
if errorlevel 1 exit /b 1

echo [2/4] Packing raw JAR (excluding raw res\Data and res\Graphics)...
"%JDK_HOME%\bin\jar" cfm build\raw.jar MANIFEST.MF ^
  -C build\classes . ^
  -C res rooms.pak ^
  -C res meta.bin ^
  -C res gfx
if errorlevel 1 exit /b 1

if exist "%PROGUARD%" (
  echo [3/4] Shrinking and preverifying with ProGuard...
  "%JDK_HOME%\bin\java" -jar "%PROGUARD%" ^
    -injars build\raw.jar ^
    -outjars dist\AoOni.jar ^
    -libraryjars "%BOOTCP%" ^
    -keep "public class * extends javax.microedition.midlet.MIDlet" ^
    -microedition ^
    -overloadaggressively ^
    -optimizationpasses 2
  if errorlevel 1 exit /b 1
) else (
  echo [3/4] ProGuard not found at %PROGUARD% - falling back to WTK preverify...
  "%WTK_HOME%\bin\preverify.exe" -classpath "%BOOTCP%" -d build\preverified build\classes
  if errorlevel 1 exit /b 1
  "%JDK_HOME%\bin\jar" cfm dist\AoOni.jar MANIFEST.MF ^
    -C build\preverified . ^
    -C res rooms.pak ^
    -C res meta.bin ^
    -C res gfx
  if errorlevel 1 exit /b 1
)

echo [4/4] Generating JAD file...
for %%A in (dist\AoOni.jar) do set JARSIZE=%%~zA
(
  echo MIDlet-Name: AoOni
  echo MIDlet-Version: 1.0.0
  echo MIDlet-Vendor: IndiePort
  echo MIDlet-1: AoOni, , AoOniMIDlet
  echo MIDlet-Jar-URL: AoOni.jar
  echo MIDlet-Jar-Size: %JARSIZE%
  echo MicroEdition-Profile: MIDP-2.0
  echo MicroEdition-Configuration: CLDC-1.1
) > dist\AoOni.jad

echo Done! dist\AoOni.jar size: %JARSIZE% bytes.
if %JARSIZE% GTR 1048576 (
  echo WARNING: JAR exceeds 1 MB Nokia 6300 limit! Re-bake graphics with --colors 32.
)
"%WTK_HOME%\bin\emulator.exe" -Xdevice:DefaultColorPhone -Xdescriptor:dist\AoOni.jad
