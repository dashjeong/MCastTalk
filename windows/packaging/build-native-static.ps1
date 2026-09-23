[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$NativeRoot,
    [Parameter(Mandatory=$true)][string]$PythonExecutable,
    [ValidateRange(1,8)][int]$Parallel = 2
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath $NativeRoot).Path
$taskRepo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (-not $taskRoot.StartsWith((Join-Path $taskRepo '.tools')+'\',[StringComparison]::OrdinalIgnoreCase)) {
    throw 'Use a dedicated build root under source/.tools'
}
$taskTools = Join-Path $taskRoot 'w64devkit/bin'
$taskCmake = Join-Path $taskTools 'cmake.exe'
$taskOldPath = $env:PATH
$taskOldCeiling = $env:GIT_CEILING_DIRECTORIES
function Invoke-Cmake([string[]]$Arguments) {
    & $taskCmake @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'Native CMake step failed' }
}
try {
    # No system PATH changes. Prevent archive builds from identifying as the
    # enclosing application's Git commit; explicit pinned revisions follow.
    $env:PATH = $taskTools+';'+$taskOldPath
    $env:GIT_CEILING_DIRECTORIES = Join-Path $taskRoot 'sources'
    $taskCommon = @('-G','Ninja','-DCMAKE_BUILD_TYPE=Release','-DBUILD_SHARED_LIBS=OFF',
        '-DGGML_STATIC=ON','-DGGML_NATIVE=OFF','-DGGML_CCACHE=OFF','-DCMAKE_EXE_LINKER_FLAGS=-static')
    Invoke-Cmake -Arguments (@('-S',"$taskRoot/sources/SPIRV-Headers-vulkan-sdk-1.4.341.0",'-B',"$taskRoot/build-spirv-headers",
        '-G','Ninja',('-DCMAKE_INSTALL_PREFIX='+$taskRoot+'/vulkan-prefix')))
    Invoke-Cmake -Arguments @('--install',"$taskRoot/build-spirv-headers")
    # Merge independent header packages in the same layout as a Vulkan SDK.
    if (-not (Test-Path "$taskRoot/sources/Vulkan-Headers-1.4.357/include/spirv")) {
        Copy-Item -LiteralPath "$taskRoot/vulkan-prefix/include/spirv" -Destination "$taskRoot/sources/Vulkan-Headers-1.4.357/include/spirv" -Recurse
    }
    & "$taskTools/dlltool.exe" -d "$taskRoot/vulkan-1.def" -l "$taskRoot/vulkan-prefix/libvulkan-1.a" -D vulkan-1.dll
    if ($LASTEXITCODE -ne 0) { throw 'Vulkan import library generation failed' }
    Invoke-Cmake -Arguments (@('-S',"$taskRoot/sources/whisper.cpp-927cfce34f31707e17f2bff35c349632fb9e2c3a",'-B',"$taskRoot/build-whisper") + $taskCommon +
        @('-DCMAKE_POLICY_VERSION_MINIMUM=3.5','-DWHISPER_BUILD_TESTS=OFF','-DWHISPER_BUILD_SERVER=OFF',
          '-DWHISPER_CURL=OFF','-DWHISPER_BUILD_IS_DEV=OFF','-DWHISPER_BUILD_COMMIT=927cfce','-DWHISPER_BUILD_NUMBER=0'))
    Invoke-Cmake -Arguments @('--build',"$taskRoot/build-whisper",'--target','whisper-cli','--parallel',"$Parallel")
    foreach ($taskVariant in @('cpu','vulkan')) {
        $taskOptions = @('-DLLAMA_BUILD_TESTS=OFF','-DLLAMA_BUILD_EXAMPLES=OFF','-DLLAMA_BUILD_TOOLS=ON',
            '-DLLAMA_OPENSSL=OFF','-DLLAMA_BUILD_UI=OFF','-DLLAMA_USE_PREBUILT_UI=OFF',
            '-DLLAMA_BUILD_COMMIT=b29c606e','-DLLAMA_BUILD_NUMBER=10964')
        if ($taskVariant -eq 'vulkan') {
            $taskOptions += @('-DGGML_VULKAN=ON',('-DVulkan_INCLUDE_DIR='+$taskRoot+'/sources/Vulkan-Headers-1.4.357/include'),
                ('-DVulkan_LIBRARY='+$taskRoot+'/vulkan-prefix/libvulkan-1.a'),
                ('-DVulkan_GLSLC_EXECUTABLE='+$taskRoot+'/vulkan-sdk/Bin/glslc.exe'),
                ('-DCMAKE_PREFIX_PATH='+$taskRoot+'/vulkan-prefix'))
        }
        Invoke-Cmake -Arguments (@('-S',"$taskRoot/sources/llama.cpp-b29c606e28a01b1bc8c1351026a0fa6e616bf6c4",'-B',"$taskRoot/build-llama-$taskVariant") + $taskCommon + $taskOptions)
        Invoke-Cmake -Arguments @('--build',"$taskRoot/build-llama-$taskVariant",'--target','llama-server','--parallel',"$Parallel")
    }
    & $PythonExecutable (Join-Path $PSScriptRoot 'stage_native_static.py') --root $taskRoot --output "$taskRoot/runtimes"
    if ($LASTEXITCODE -ne 0) { throw 'Native runtime verification/staging failed' }
} finally {
    $env:PATH = $taskOldPath
    $env:GIT_CEILING_DIRECTORIES = $taskOldCeiling
}
# Build tools/SDKs are not installed on the host and are not shipped in the app.
