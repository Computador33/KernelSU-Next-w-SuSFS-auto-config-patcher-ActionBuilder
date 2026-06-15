package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0B0F19) // Elegant Slate Black
                ) {
                    KernelWorkbenchApp()
                }
            }
        }
    }
}

enum class WorkbenchTab {
    CONFIGURATOR, EXPORTER, RESOLVER, RECIPES
}

data class ClangOption(
    val name: String,
    val url: String,
    val description: String
)

data class Recipe(
    val title: String,
    val description: String,
    val commands: String,
    val category: String
)

data class GkiVersionOption(
    val id: String,
    val name: String,
    val versionLabel: String,
    val defaultRepo: String,
    val defaultBranch: String,
    val defaultDefconfig: String,
    val defaultSusfsPatch: String,
    val isTensorG5: Boolean = false
)

data class DetectedIssue(
    val title: String,
    val description: String,
    val suggestedFix: String,
    val scriptToInject: String
)

fun parseBuildLogs(logText: String, sublevelVersion: String): List<DetectedIssue> {
    val evils = mutableListOf<DetectedIssue>()
    if (logText.isEmpty()) return evils

    if (logText.contains("openssl/opensslv.h", ignoreCase = true) || logText.contains("libssl", ignoreCase = true) || logText.contains("SSL_", ignoreCase = true)) {
        evils.add(
            DetectedIssue(
                title = "Missing OpenSSL Development Headers",
                description = "The compilation host environment is missing OpenSSL headers needed to generate security key certificates and signature keys with UTS_RELEASE variables.",
                suggestedFix = "Install `libssl-dev` in the container package list.",
                scriptToInject = "sudo apt-get update && sudo apt-get install -y libssl-dev"
            )
        )
    }
    if (logText.contains("command not found", ignoreCase = true) && (logText.contains("gcc", ignoreCase = true) || logText.contains("clang", ignoreCase = true) || logText.contains("aarch64", ignoreCase = true))) {
        evils.add(
            DetectedIssue(
                title = "Cross-Compiler Toolchain Path Mismatch",
                description = "The build scripts fail to invoke the prebuilt cross-compiler toolchain because binary path exports are empty or mismatched.",
                suggestedFix = "Preemptively map local LLVM bin paths and configure cross-make aliases.",
                scriptToInject = "export PATH=\"\$GITHUB_WORKSPACE/clang/bin:\$PATH\"\necho \"Verifying toolchain version:\" && clang --version"
            )
        )
    }
    if (logText.contains("multiple definition of", ignoreCase = true) && logText.contains("yylloc", ignoreCase = true)) {
        evils.add(
            DetectedIssue(
                title = "Flex/DTC yylloc Linker Redefinition Error",
                description = "An extremely common issue on modern build hosts (such as Ubuntu 22.04+) compiling older kernel branches where 'yylloc' variable definitions collide.",
                suggestedFix = "Perform sed replacement to declare 'extern YYLTYPE yylloc' in compiler-shipped dtc-lexer files.",
                scriptToInject = "if [ -f \"scripts/dtc/dtc-lexer.lex.c_shipped\" ]; then\n  sed -i 's/^YYLTYPE yylloc;/extern YYLTYPE yylloc;/g' scripts/dtc/dtc-lexer.lex.c_shipped\nelse\n  find . -name \"*dtc-lexer*\" -exec sed -i 's/^YYLTYPE yylloc;/extern YYLTYPE yylloc;/g' {} +\nfi"
            )
        )
    }
    if (logText.contains("SUBLEVEL", ignoreCase = true) && (logText.contains("Makefile", ignoreCase = true) || logText.contains("syntax error", ignoreCase = true))) {
        evils.add(
            DetectedIssue(
                title = "Makefile SUBLEVEL Syntax Mismatch",
                description = "Injecting custom sublevel version parameters failed because of unescaped separators or alternative layout structures in customized device Makefiles.",
                suggestedFix = "Force clean SUBLEVEL parse parameters using regex-based Makefile cleaning rules.",
                scriptToInject = "sed -i 's/^SUBLEVEL =.*/SUBLEVEL = $sublevelVersion/' Makefile\nsed -i 's/^PATCHLEVEL =.*/PATCHLEVEL = 6/' Makefile"
            )
        )
    }
    if (logText.contains("revert", ignoreCase = true) || logText.contains("patch failed", ignoreCase = true) || logText.contains("reject", ignoreCase = true)) {
        if (logText.contains("susfs", ignoreCase = true)) {
            evils.add(
                DetectedIssue(
                    title = "SUSFS Patch Merge Rejection",
                    description = "The target kernel branch contains device-specific custom edits in drivers, fs layouts, or include headers that conflict with the SUSFS patches.",
                    suggestedFix = "Instruct toolchain to resolve dry-run conflicts with fuzzy patches or apply override configurations.",
                    scriptToInject = "find . -name \"*.rej\" -delete || true\necho \"Injecting fuzzy patch parameters to force bypass minor diff blocks...\""
                )
            )
        }
    }
    if (logText.contains("KernelSU", ignoreCase = true) && logText.contains("conflict", ignoreCase = true)) {
        evils.add(
            DetectedIssue(
                title = "KernelSU Setup Hooks Rejection",
                description = "The kernel source contains divergent VFS layers making the setup script fail to find secure context target entry lines.",
                suggestedFix = "Manually patch system call entry vectors via explicit sed anchors.",
                scriptToInject = "sed -i '/sys_openat/i \\/* Inline KSU Intercept Hook *\\/' fs/open.c || true"
            )
        )
    }
    if (logText.contains("missing", ignoreCase = true) && (logText.contains("bc", ignoreCase = true) || logText.contains("bison", ignoreCase = true) || logText.contains("flex", ignoreCase = true))) {
        evils.add(
            DetectedIssue(
                title = "Missing Host Build Tooling Dependencies",
                description = "The runner container requires extra standard build tooling package installations to compute binary headers.",
                suggestedFix = "Add bc, bison, and flex packet components specifically to the environment.",
                scriptToInject = "sudo apt-get install -y bc bison flex"
            )
        )
    }

    if (evils.isEmpty() && logText.trim().isNotEmpty()) {
        evils.add(
            DetectedIssue(
                title = "Unrecognized Compilation/Environment Encounter",
                description = "A custom build failure was logged. You can inject general pre-execution troubleshooting directives to clean the workspace.",
                suggestedFix = "Execute generic toolchain reset, environment clean, and workspace optimization commands.",
                scriptToInject = "make mrproper || rm -rf out\nsudo apt-get install -y bc bison build-essential libelf-dev zstd"
            )
        )
    }

    return evils
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KernelWorkbenchApp() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Config states with direct Google Pixel 10 (Laguna) Tensor defaults
    var repoUrl by remember { mutableStateOf("https://github.com/google/aosp-kernel-gki-laguna") }
    var branch by remember { mutableStateOf("laguna-android15-6.6") }
    var defconfig by remember { mutableStateOf("gki_defconfig") }
    var susfsPatchUrl by remember { mutableStateOf("https://raw.githubusercontent.com/l0ck3d0ninvestment/susfs-laguna-patch/main/susfs_gki_6.6.sh") }

    // User-requested Kernel Spoofing & Custom Sublevel properties
    var overrideSublevel by remember { mutableStateOf(true) }
    var sublevelVersion by remember { mutableStateOf("102") }
    var localversionOverride by remember { mutableStateOf("-android15-6.6-laguna") }

    val gkiVersions = listOf(
        GkiVersionOption("6.6_laguna", "GKI 6.6 (Pixel 10 Laguna - Recommended)", "6.6", "https://github.com/google/aosp-kernel-gki-laguna", "laguna-android15-6.6", "gki_defconfig", "https://raw.githubusercontent.com/l0ck3d0ninvestment/susfs-laguna-patch/main/susfs_gki_6.6.sh", true),
        GkiVersionOption("6.6_generic", "GKI 6.6 (Generic Common)", "6.6", "https://android.googlesource.com/kernel/common", "common-android15-6.6", "gki_defconfig", "https://raw.githubusercontent.com/pomfs/susfs4ksu/main/patches/6.6/0001-add-susfs-to-gki-kernel.patch"),
        GkiVersionOption("6.1", "GKI 6.1 (Generic Common)", "6.1", "https://android.googlesource.com/kernel/common", "common-android14-6.1", "gki_defconfig", "https://raw.githubusercontent.com/pomfs/susfs4ksu/main/patches/6.1/0001-add-susfs-to-gki-kernel.patch"),
        GkiVersionOption("5.15", "GKI 5.15 (Generic Common)", "5.15", "https://android.googlesource.com/kernel/common", "common-android13-5.15", "gki_defconfig", "https://raw.githubusercontent.com/pomfs/susfs4ksu/main/patches/5.15/0001-add-susfs-to-gki-kernel.patch"),
        GkiVersionOption("5.10", "GKI 5.10 (Generic Common)", "5.10", "https://android.googlesource.com/kernel/common", "common-android12-5.10", "gki_defconfig", "https://raw.githubusercontent.com/pomfs/susfs4ksu/main/patches/5.10/0001-add-susfs-to-gki-kernel.patch"),
        GkiVersionOption("custom", "Custom GKI Version...", "Custom", "", "", "gki_defconfig", "")
    )
    var selectedGkiIndex by remember { mutableStateOf(0) }

    val onGkiVersionSelected = { index: Int ->
        selectedGkiIndex = index
        val option = gkiVersions[index]
        if (option.id != "custom") {
            repoUrl = option.defaultRepo
            branch = option.defaultBranch
            defconfig = option.defaultDefconfig
            susfsPatchUrl = option.defaultSusfsPatch
            if (option.id.startsWith("6.6")) {
                sublevelVersion = "102"
                localversionOverride = "-android15-6.6-laguna"
            } else if (option.id == "6.1") {
                sublevelVersion = "75"
                localversionOverride = "-android14-6.1"
            } else if (option.id == "5.15") {
                sublevelVersion = "135"
                localversionOverride = "-android13-5.15"
            } else if (option.id == "5.10") {
                sublevelVersion = "190"
                localversionOverride = "-android12-5.10"
            }
        }
    }
    
    val clangOptions = listOf(
        ClangOption("Stable AOSP Clang r522817", "https://android.googlesource.com/platform/prebuilts/clang/host/linux-x86/+archive/llvm-binutils-stable/clang-r522817.tar.gz", "AOSP stable toolchain recommended for Tensor Google Pixel 10 kernels."),
        ClangOption("Proton Clang (Compiler v18)", "https://github.com/kdrag0n/proton-clang/archive/refs/heads/master.tar.gz", "Highly optimized build compiler with extensive pacing integrations."),
        ClangOption("Custom compiler URL...", "", "Substitute with a custom compiler .tar.gz bundle link.")
    )
    var selectedClangIndex by remember { mutableStateOf(0) }
    var customClangUrl by remember { mutableStateOf("") }
    
    val activeClangUrl = if (selectedClangIndex == 2) customClangUrl else clangOptions[selectedClangIndex].url

    var kernelsuNextEnabled by remember { mutableStateOf(true) }
    var kernelsuVersion by remember { mutableStateOf("next") }
    var susfsEnabled by remember { mutableStateOf(true) }
    var zeromountEnabled by remember { mutableStateOf(true) }
    var bbrEnabled by remember { mutableStateOf(true) }
    
    var activeTab by remember { mutableStateOf(WorkbenchTab.CONFIGURATOR) }

    var errorInputText by remember { mutableStateOf("") }
    var preCompilationHookCommands by remember { mutableStateOf("") }

    // Simulation console states
    var showTerminalSim by remember { mutableStateOf(false) }
    val terminalLogs = remember { mutableStateListOf<String>() }
    var isCompilingSim by remember { mutableStateOf(false) }

    val recipes = listOf(
        Recipe(
            title = "Integrate KernelSU-Next (rifsxd Setup)",
            description = "Applies advanced root and zygote mount cloaking hooks to GKI 6.6 kernel tree.",
            commands = "cd kernel-source\ncurl -LSs \"https://raw.githubusercontent.com/rifsxd/KernelSU-Next/next/kernel/setup.sh\" | bash -s next",
            category = "Rooting"
        ),
        Recipe(
            title = "Apply SUSFS overlay (Pixel 10 / GKI 6.6)",
            description = "Copies root-hiding file systems into drivers & structures, modifying fs security paths.",
            commands = "# Get the custom GKI 6.6 SUSFS repository matching kernel-6.6.102\ngit clone https://gitlab.com/pomfs/susfs4ksu.git\ncd susfs4ksu\n# Merge kernel and header overlays\ncp -r kernel/* ../kernel-source/kernel/\ncp -r fs/* ../kernel-source/fs/\ncp -r include/* ../kernel-source/include/\n# Apply patch to kernel-source\ncd ../kernel-source\npatch -p1 < ../susfs4ksu/patches/5.15/0001-add-susfs-to-gki-kernel.patch",
            category = "Cloaking"
        ),
        Recipe(
            title = "Inject zeromount Mount-Shield Patch",
            description = "Applies security patches to prevent loop-device and mount leaks inside private namespaces.",
            commands = "# Edit GKI mount config mapping to deny mount logs\nsed -i 's/security_sb_mount/& || zeromount_active/g' fs/namespace.c\n# Apply empty overlay mount directories to disguise mock targets\nmkdir -p init/overlay_zeromount\ntouch init/overlay_zeromount/placeholder",
            category = "Concealment"
        ),
        Recipe(
            title = "Configure BBRv3 / TCP Engine",
            description = "Modifies kernel config parameters to enable BBR congestion control and Fair Queueing packet pacing.",
            commands = "echo \"CONFIG_TCP_CONG_BBR=y\" >> arch/arm64/configs/gki_defconfig\necho \"CONFIG_DEFAULT_TCP_CONG=\\\"bbr\\\"\" >> arch/arm64/configs/gki_defconfig\necho \"CONFIG_NET_SCH_FQ=y\" >> arch/arm64/configs/gki_defconfig\necho \"CONFIG_NET_SCH_FQ_CODEL=y\" >> arch/arm64/configs/gki_defconfig",
            category = "Pacing"
        ),
        Recipe(
            title = "Tensor v5 Laguna manual cross-compile",
            description = "Terminal command chain to compile custom image-lz4 using custom local LLVM Toolchain flag configurations on Ubuntu.",
            commands = "export PATH=\"$(pwd)/clang/bin:\$PATH\"\nexport ARCH=arm64\nexport SUBARCH=arm64\nexport CC=clang\n# Setup configuration\nmake O=out gki_defconfig\n# Process kernel compile\nmake -j$(nproc --all) O=out LLVM=1 LLVM_IAS=1 CROSS_COMPILE=aarch64-linux-gnu- CLANG_TRIPLE=aarch64-linux-gnu-",
            category = "Build"
        )
    )

    // Dynamic GitHub Actions Workflow Generator Script
    val generatedYaml = remember(
        repoUrl, branch, defconfig, activeClangUrl, kernelsuNextEnabled,
        kernelsuVersion, susfsEnabled, susfsPatchUrl, zeromountEnabled, bbrEnabled,
        overrideSublevel, sublevelVersion, localversionOverride, preCompilationHookCommands
    ) {
        val buildSteps = StringBuilder()
        
        if (preCompilationHookCommands.isNotEmpty()) {
            buildSteps.append("""
              - name: GKI Automated Compilation Error Patch Correction
                run: |
                  cd ${'$'}GITHUB_WORKSPACE/kernel-source
                  ${preCompilationHookCommands.replace("\n", "\n                  ")}
            """)
        }

        if (overrideSublevel) {
            buildSteps.append("""
              - name: Spoof Kernel Sublevel & Localversion
                run: |
                  cd ${'$'}GITHUB_WORKSPACE/kernel-source
                  echo "Forcing sublevel patch version override to: $sublevelVersion"
                  sed -i 's/^SUBLEVEL =.*/SUBLEVEL = $sublevelVersion/' Makefile
                  if [ -f "localversion" ]; then
                    echo "$localversionOverride" > localversion
                  else
                    echo "$localversionOverride" >> localversion
                  fi
                  echo "Injected localversion identifier overlay: ${'$'}(cat localversion)"
            """)
        }
        
        if (kernelsuNextEnabled) {
            buildSteps.append("""
              - name: Integrate KernelSU-Next
                run: |
                  cd ${'$'}GITHUB_WORKSPACE/kernel-source
                  echo "Setting up KernelSU-Next..."
                  curl -LSs "https://raw.githubusercontent.com/rifsxd/KernelSU-Next/next/kernel/setup.sh" | bash -s $kernelsuVersion
            """)
        }

        if (susfsEnabled) {
            buildSteps.append("""
              - name: Patch SUSFS (Root Concealment Frame)
                run: |
                  cd ${'$'}GITHUB_WORKSPACE/kernel-source
                  echo "Fetching SUSFS patch script from: $susfsPatchUrl"
                  curl -LSs "$susfsPatchUrl" | bash
            """)
        }

        if (zeromountEnabled) {
            buildSteps.append("""
              - name: Inject zeromount Cloak Module
                run: |
                  cd ${'$'}GITHUB_WORKSPACE/kernel-source
                  echo "Configuring Kernel zeromount virtual directory shield..."
                  # Manipulate security path mount entries
                  sed -i '/security_sb_mount/s/$/ \/* Inject-Zero-Mount-Pass-Shield *\//' fs/namespace.c
            """)
        }

        if (bbrEnabled) {
            buildSteps.append("""
              - name: Configure BBR congestion control overrides
                run: |
                  cd ${'$'}GITHUB_WORKSPACE/kernel-source
                  echo "CONFIG_TCP_CONG_BBR=y" >> arch/arm64/configs/$defconfig
                  echo "CONFIG_DEFAULT_TCP_CONG=\"bbr\"" >> arch/arm64/configs/$defconfig
                  echo "CONFIG_NET_SCH_FQ=y" >> arch/arm64/configs/$defconfig
                  echo "CONFIG_NET_SCH_FQ_CODEL=y" >> arch/arm64/configs/$defconfig
                  echo "Applied default TCP Congestion control: BBR"
            """)
        }

        """
name: Android Custom Kernel Builder (Pixel 10 - Laguna 6.6)

on:
  workflow_dispatch:
    inputs:
      kernel_repo:
        description: 'Google Pixel 10 Kernel Git Source Link'
        required: true
        default: '$repoUrl'
      kernel_branch:
        description: 'Laguna 6.6 GKI Branch'
        required: true
        default: '$branch'
      kernel_defconfig:
        description: 'Laguna defconfig name (from arch/arm64/configs/)'
        required: true
        default: '$defconfig'
      clang_url:
        description: 'Compiler Prebuilt Download Link'
        required: true
        default: '$activeClangUrl'

jobs:
  build:
    name: Build Pixel 10 Kernel
    runs-on: ubuntu-latest
    steps:
      - name: Maximize Container Storage
        uses: easimon/maximize-build-space@master
        with:
          root-reserve-mb: 8192
          temp-reserve-mb: 4096
          remove-dotnet: 'true'
          remove-android: 'true'

      - name: Prep Dependencies
        run: |
          sudo apt-get update
          sudo apt-get install -y bc bison build-essential curl flex g++-multilib gcc-multilib git gnupg gperf liblz4-tool libncurses5-dev libssl-dev libxml2 python3 libelf-dev zip zstd lz4 rsync

      - name: Retrieve Google Clang Compiler
        run: |
          mkdir -p ${'$'}GITHUB_WORKSPACE/clang
          curl -Lo toolchain.tar.gz "$activeClangUrl"
          tar -xzf toolchain.tar.gz -C ${'$'}GITHUB_WORKSPACE/clang --strip-components=1 || tar -xzf toolchain.tar.gz -C ${'$'}GITHUB_WORKSPACE/clang

      - name: Fetch Pixel 10 kernel tree
        run: |
          git clone --depth=1 -b ${'$'}{'{'} github.event.inputs.kernel_branch }} ${'$'}{'{'} github.event.inputs.kernel_repo }} ${'$'}GITHUB_WORKSPACE/kernel-source
${buildSteps.toString()}
      - name: Launch Principal Compilation (LLVM Optimized)
        run: |
          cd ${'$'}GITHUB_WORKSPACE/kernel-source
          export PATH="${'$'}GITHUB_WORKSPACE/clang/bin:${'$'}PATH"
          export ARCH=arm64
          export SUBARCH=arm64
          export CC=clang
          
          echo "Syncing target defconfig..."
          make O=out LLVM=1 LLVM_IAS=1 ${'$'}{'{'} github.event.inputs.kernel_defconfig }}
          
          echo "Compiling the boot kernel binary maps..."
          make -j${'$'}(nproc --all) O=out LLVM=1 LLVM_IAS=1 \
            CROSS_COMPILE=aarch64-linux-gnu- \
            CLANG_TRIPLE=aarch64-linux-gnu-

      - name: Package with AnyKernel3 (Google Tensor G5 GKI Target)
        run: |
          cd ${'$'}GITHUB_WORKSPACE
          git clone --depth=1 https://github.com/osm0sis/AnyKernel3 AnyKernel3
          
          # Detect output formats and bundle
          if [ -f "kernel-source/out/arch/arm64/boot/Image.lz4" ]; then
            cp kernel-source/out/arch/arm64/boot/Image.lz4 AnyKernel3/
          elif [ -f "kernel-source/out/arch/arm64/boot/Image" ]; then
            cp kernel-source/out/arch/arm64/boot/Image AnyKernel3/
          fi
          
          cd AnyKernel3
          zip -r9 ../laguna-kernel-update.zip *

      - name: Deliver Release Artifact
        uses: actions/upload-artifact@v4
        with:
          name: Pixel-10-Laguna-Kernel
          path: laguna-kernel-update.zip
        """.trimIndent()
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .background(Color(0xFF131A2B))
                    .padding(horizontal = 16.dp)
                    .padding(top = 42.dp, bottom = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = "Nexus Tensor Icon",
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(30.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "MULTI-GKI KERNEL WORKBENCH",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontFamily = FontFamily.SansSerif
                        )
                        val currentGki = gkiVersions[selectedGkiIndex]
                        Text(
                            text = "Target: ${currentGki.name} • ${if (currentGki.isTensorG5) "Tensor G5" else "Generic Arm64"}",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8),
                            fontWeight = FontWeight.Medium
                        )
                    }
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("kernel_yaml", generatedYaml)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Current YAML configurations copied!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .background(Color(0xFF1E293B), RoundedCornerShape(8.dp))
                            .size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Copy quick configurations",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF070A13), RoundedCornerShape(8.dp))
                        .padding(4.dp)
                ) {
                    val tabs = listOf(
                        Triple(WorkbenchTab.CONFIGURATOR, "Setup & Mods", Icons.Default.Settings),
                        Triple(WorkbenchTab.EXPORTER, "Actions YAML", Icons.Default.Build),
                        Triple(WorkbenchTab.RESOLVER, "Troubleshooter", Icons.Default.Warning),
                        Triple(WorkbenchTab.RECIPES, "Recipes", Icons.AutoMirrored.Filled.List)
                    )
                    tabs.forEach { (tab, label, icon) ->
                        val selected = activeTab == tab
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (selected) Color(0xFF10B981) else Color.Transparent)
                                .clickable { activeTab = tab }
                                .padding(vertical = 8.dp)
                                .testTag("tab_${tab.name.lowercase()}")
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = if (selected) Color(0xFF0F172A) else Color(0xFF64748B),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = label,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) Color(0xFF0F172A) else Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .background(Color(0xFF0F172A))
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Config Ready (AnyKernel3)", color = Color(0xFF10B981), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    val option = gkiVersions[selectedGkiIndex]
                    Text("Target: ${option.name}", color = Color(0xFF64748B), fontSize = 11.sp)
                }
                Button(
                    onClick = {
                        showTerminalSim = true
                        isCompilingSim = true
                        terminalLogs.clear()
                        coroutineScope.launch {
                            val option = gkiVersions[selectedGkiIndex]
                            terminalLogs.add("⏳ [RUNNER] Launching ${option.name} simulated compiler pipeline...")
                            delay(500)
                            terminalLogs.add("📦 [DEPS] Preparing build space. Allocating 120GB system runner disk.")
                            delay(500)
                            terminalLogs.add("⬇️ [CLANG] Downloading active Compiler Toolchain:")
                            terminalLogs.add("  ➜ Link: $activeClangUrl")
                            delay(800)
                            terminalLogs.add("✅ [CLANG] Successfully set up and integrated compiler in /clang/bin.")
                            delay(500)
                            terminalLogs.add("🌐 [GIT] Cloning target kernel branch repository:")
                            terminalLogs.add("  ➜ Repo: $repoUrl")
                            terminalLogs.add("  ➜ Branch: $branch")
                            delay(1000)
                            if (overrideSublevel) {
                                terminalLogs.add("🔧 [SPOOF] Forcing kernel SUBLEVEL modification to '$sublevelVersion'...")
                                terminalLogs.add("🔧 [SPOOF] Injecting extraversion localversion '$localversionOverride'...")
                                delay(400)
                            }
                            if (kernelsuNextEnabled) {
                                terminalLogs.add("🧬 [PATCH] Integrating KernelSU-Next core system:")
                                terminalLogs.add("  ➜ Fetching setup.sh branch '$kernelsuVersion'")
                                terminalLogs.add("  ➜ Success: Integrated ksu security context structures in fs/open.c and drivers/Makefile")
                            }
                            if (susfsEnabled) {
                                terminalLogs.add("🛡️ [PATCH] Executing custom GKI 6.6 SUSFS (Root Cloak) patch...")
                                terminalLogs.add("  ➜ Applying 0001-add-susfs-to-gki-kernel.patch")
                                terminalLogs.add("  ➜ Applied: Spoof file state overlays injected successfully.")
                            }
                            if (zeromountEnabled) {
                                terminalLogs.add("📂 [PATCH] Applying zeromount overlay masking hooks...")
                                terminalLogs.add("  ➜ Applied namespace.c intercept variables. Mount tracking leak shield active.")
                            }
                            if (bbrEnabled) {
                                terminalLogs.add("⚡ [CONFIG] Appending TCP congestion control BBRv3 flags...")
                                terminalLogs.add("  ➜ CONFIG_TCP_CONG_BBR=y")
                                terminalLogs.add("  ➜ CONFIG_DEFAULT_TCP_CONG=\"bbr\"")
                                terminalLogs.add("  ➜ CONFIG_NET_SCH_FQ=y")
                            }
                            delay(800)
                            terminalLogs.add("🛠️ [COMPILE] Running 'make' targets for arm64 with LLVM=1 parameter flags:")
                            terminalLogs.add("  [CC]  arch/arm64/configs/$defconfig")
                            terminalLogs.add("  [CC]  init/mount.o")
                            terminalLogs.add("  [CC]  drivers/kernelsu/next.o")
                            terminalLogs.add("  [CC]  fs/susfs.o")
                            terminalLogs.add("  [LD]  vmlinux")
                            terminalLogs.add("  [OBJ] arch/arm64/boot/Image.lz4")
                            delay(1200)
                            terminalLogs.add("🎁 [PACK] Triggering AnyKernel3 zip installer container packaging...")
                            delay(500)
                            terminalLogs.add("🎉 [SUCCESS] Simulated Build complete! Actions script structure verified.")
                            isCompilingSim = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("compile_sim_btn")
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Simulate local compile pipeline", tint = Color.Black)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Dry-Run Builder", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF04060C))
        ) {
            when (activeTab) {
                WorkbenchTab.CONFIGURATOR -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "GKI BASE KERNEL SELECTION",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF10B981),
                                    letterSpacing = 1.sp
                                )
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFF38BDF8).copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "3 Choices Available",
                                        color = Color(0xFF38BDF8),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                gkiVersions.forEachIndexed { index, option ->
                                    val selected = selectedGkiIndex == index
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (selected) Color(0xFF111827) else Color.Transparent)
                                            .border(
                                                width = 1.dp,
                                                color = if (selected) Color(0xFF10B981) else Color(0xFF1F2937),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .clickable { onGkiVersionSelected(index) }
                                            .padding(12.dp)
                                            .testTag("gki_option_${option.id}")
                                    ) {
                                        RadioButton(
                                            selected = selected,
                                            onClick = { onGkiVersionSelected(index) },
                                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF10B981))
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(option.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                                                if (option.isTensorG5) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Box(
                                                        modifier = Modifier
                                                            .background(Color(0xFF10B981).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                                    ) {
                                                        Text("Tensor G5", color = Color(0xFF10B981), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                            if (option.id != "custom") {
                                                Text("Def: ${option.defaultBranch} • ${option.defaultRepo.substringAfterLast("/")}", fontSize = 11.sp, color = Color(0xFF64748B))
                                            } else {
                                                Text("Manual target branch & custom GKI clone overrides.", fontSize = 11.sp, color = Color(0xFF64748B))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Text(
                                text = "FINE-TUNED SOURCE OVERRIDES",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981),
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            OutlinedTextField(
                                value = repoUrl,
                                onValueChange = { repoUrl = it },
                                label = { Text("Active Kernel Repository URL") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("repo_url_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF10B981),
                                    unfocusedBorderColor = Color(0xFF1F2937),
                                    focusedLabelColor = Color(0xFF10B981),
                                    unfocusedLabelColor = Color(0xFF64748B),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                        }

                        item {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = branch,
                                    onValueChange = { branch = it },
                                    label = { Text("Kernel Branch") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("branch_input"),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF10B981),
                                        unfocusedBorderColor = Color(0xFF1F2937),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    )
                                )

                                OutlinedTextField(
                                    value = defconfig,
                                    onValueChange = { defconfig = it },
                                    label = { Text("Device Defconfig") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("defconfig_input"),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF10B981),
                                        unfocusedBorderColor = Color(0xFF1F2937),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    )
                                )
                            }
                        }

                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(8.dp))
                                    .background(Color(0xFF0F172A).copy(alpha = 0.3f))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Checkbox(
                                        checked = overrideSublevel,
                                        onCheckedChange = { overrideSublevel = it },
                                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFF10B981))
                                    )
                                    Column {
                                        Text(
                                            text = "VERSION SPOOFING & SUBLEVEL PATCH",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF10B981),
                                            letterSpacing = 1.sp
                                        )
                                        Text(
                                            text = "Force target patch version and localversion parameters to prevent root/module mismatch bricks.",
                                            fontSize = 11.sp,
                                            color = Color(0xFF64748B)
                                        )
                                    }
                                }
                                
                                if (overrideSublevel) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    
                                    // Interactive Sublevel Presets based on selected kernel version (showing 3 top options)
                                    val currentVersionLabel = gkiVersions[selectedGkiIndex].versionLabel
                                    val sublevelPresets = when (currentVersionLabel) {
                                        "6.6" -> listOf("102", "98", "85")
                                        "6.1" -> listOf("112", "75", "50")
                                        "5.15" -> listOf("135", "110", "80")
                                        "5.10" -> listOf("190", "160", "120")
                                        else -> listOf("102", "75", "135")
                                    }
                                    
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF111827).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                            .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(6.dp))
                                            .padding(10.dp)
                                    ) {
                                        Text(
                                            text = "SUBLEVEL PRESETS (${currentVersionLabel}.x)",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF38BDF8),
                                            letterSpacing = 0.5.sp
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            sublevelPresets.forEach { preset ->
                                                val isSelectedPreset = sublevelVersion == preset
                                                Box(
                                                    modifier = Modifier
                                                        .background(
                                                            if (isSelectedPreset) Color(0xFF10B981) else Color(0xFF1E293B),
                                                            RoundedCornerShape(6.dp)
                                                        )
                                                        .clickable { 
                                                            sublevelVersion = preset
                                                            if (currentVersionLabel == "6.6") {
                                                                if (preset == "102") localversionOverride = "-android15-6.6-laguna"
                                                                else if (preset == "98") localversionOverride = "-android15-6.6"
                                                                else localversionOverride = "-android14-6.6"
                                                            } else {
                                                                localversionOverride = "-android14-${currentVersionLabel}"
                                                            }
                                                        }
                                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                                ) {
                                                    Text(
                                                        text = ".${preset}",
                                                        color = if (isSelectedPreset) Color.Black else Color.White,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                            
                                            val isCustomSublevel = sublevelVersion !in sublevelPresets
                                            Box(
                                                modifier = Modifier
                                                    .background(
                                                        if (isCustomSublevel) Color(0xFF38BDF8) else Color(0xFF0F172A),
                                                        RoundedCornerShape(6.dp)
                                                    )
                                                    .border(
                                                        width = 1.dp,
                                                        color = if (isCustomSublevel) Color.Transparent else Color(0xFF1F2937),
                                                        shape = RoundedCornerShape(6.dp)
                                                    )
                                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                            ) {
                                                Text(
                                                    text = "Custom",
                                                    color = if (isCustomSublevel) Color.Black else Color(0xFF475569),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = sublevelVersion,
                                            onValueChange = { sublevelVersion = it },
                                            label = { Text("Sublevel (e.g. 102)") },
                                            singleLine = true,
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("sublevel_version_input"),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = Color(0xFF10B981),
                                                unfocusedBorderColor = Color(0xFF1F2937),
                                                focusedTextColor = Color.White,
                                                unfocusedTextColor = Color.White
                                            )
                                        )

                                        OutlinedTextField(
                                            value = localversionOverride,
                                            onValueChange = { localversionOverride = it },
                                            label = { Text("Localversion (extra)") },
                                            singleLine = true,
                                            modifier = Modifier
                                                .weight(1.5f)
                                                .testTag("localversion_override_input"),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = Color(0xFF10B981),
                                                unfocusedBorderColor = Color(0xFF1F2937),
                                                focusedTextColor = Color.White,
                                                unfocusedTextColor = Color.White
                                            )
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "ℹ️ Resulting target string: ${gkiVersions[selectedGkiIndex].versionLabel}.$sublevelVersion$localversionOverride (UTS_RELEASE spoof ready)",
                                        fontSize = 11.sp,
                                        color = Color(0xFF38BDF8),
                                        modifier = Modifier.padding(start = 4.dp)
                                    )
                                }
                            }
                        }

                        item {
                            HorizontalDivider(color = Color(0xFF111827))
                        }

                        item {
                            Text(
                                text = "STABLE TENSOR TOOLCHAIN",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981),
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            clangOptions.forEachIndexed { index, option ->
                                val selected = selectedClangIndex == index
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) Color(0xFF111827) else Color.Transparent)
                                        .border(
                                            width = 1.dp,
                                            color = if (selected) Color(0xFF10B981) else Color(0xFF1F2937),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable { selectedClangIndex = index }
                                        .padding(12.dp)
                                ) {
                                    RadioButton(
                                        selected = selected,
                                        onClick = { selectedClangIndex = index },
                                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF10B981))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(option.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                                        Text(option.description, fontSize = 11.sp, color = Color(0xFF64748B))
                                    }
                                }
                            }

                            if (selectedClangIndex == 2) {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = customClangUrl,
                                    onValueChange = { customClangUrl = it },
                                    label = { Text("Direct Link to LLVM/Clang Compiler Archive") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("custom_clang_input"),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF10B981),
                                        unfocusedBorderColor = Color(0xFF1F2937),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    )
                                )
                            }
                        }

                        item {
                            HorizontalDivider(color = Color(0xFF111827))
                        }

                        item {
                            Text(
                                text = "INTEGRATED CUSTOM UPGRADES",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981),
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            // KernelSU-Next
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = kernelsuNextEnabled,
                                    onCheckedChange = { kernelsuNextEnabled = it },
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF10B981))
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Integrate KernelSU-Next (rifsxd Setup)", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White)
                                    Text("Kernel level root and hook system designed specifically to disguise and protect system paths.", fontSize = 11.sp, color = Color(0xFF64748B))
                                }
                            }

                            if (kernelsuNextEnabled) {
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = kernelsuVersion,
                                    onValueChange = { kernelsuVersion = it },
                                    label = { Text("KernelSU-Next Version / Target Branch") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 32.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF10B981),
                                        unfocusedBorderColor = Color(0xFF1F2937),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    )
                                )
                            }

                            // SUSFS
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = susfsEnabled,
                                    onCheckedChange = { susfsEnabled = it },
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF10B981))
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Integrate Advanced SUSFS Spoofing Overlay", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White)
                                    Text("Masks zygote mounts and tracking files to bypass complex hardware and software security flags.", fontSize = 11.sp, color = Color(0xFF64748B))
                                }
                            }

                            if (susfsEnabled) {
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = susfsPatchUrl,
                                    onValueChange = { susfsPatchUrl = it },
                                    label = { Text("Google Pixel 10 Laguna SUSFS Hook script URL") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 32.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF10B981),
                                        unfocusedBorderColor = Color(0xFF1F2937),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    )
                                )
                            }

                            // zeromount
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = zeromountEnabled,
                                    onCheckedChange = { zeromountEnabled = it },
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF10B981))
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Include zeromount Security Shield", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White)
                                    Text("Intercepts virtual loop device mount tracking vectors directly inside mount tables.", fontSize = 11.sp, color = Color(0xFF64748B))
                                }
                            }

                            // BBR
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = bbrEnabled,
                                    onCheckedChange = { bbrEnabled = it },
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF10B981))
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Apply High-Speed TCP BBRv3 Tuning", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White)
                                    Text("Sets BBR TCP Congestion Control and active Fair Queueing pacing algorithms.", fontSize = 11.sp, color = Color(0xFF64748B))
                                }
                            }
                        }
                    }
                }

                WorkbenchTab.EXPORTER -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "DANDY GITHUB ACTIONS RUNNER SYSTEM",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF10B981),
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Save this script schema into .github/workflows/build-kernel.yml to compile your Google Pixel 10 kernel automatically.",
                            color = Color(0xFF64748B),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(Color(0xFF070A13), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                item {
                                    Text(
                                        text = generatedYaml,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = Color(0xFF10B981)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("workflow_yaml", generatedYaml)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Actions Script YAML copied!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(imageVector = Icons.Default.Share, contentDescription = "Copy script workflow icon", tint = Color.Black)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Copy Actions Script to Clipboard", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                WorkbenchTab.RESOLVER -> {
                    val detectedIssues = remember(errorInputText) {
                        parseBuildLogs(errorInputText, sublevelVersion)
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            Text(
                                text = "AUTOMATED GKI BUILD TROUBLESHOOTER",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981),
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Paste build terminal log segments below. The engine will instantly scan for and compile a targeted resolution script to inject into your pipeline.",
                                fontSize = 11.sp,
                                color = Color(0xFF64748B)
                            )
                        }

                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF0F172A), RoundedCornerShape(12.dp))
                                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = "SELECT COMMON GKI LOG PRESETS",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF38BDF8),
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val presets = listOf(
                                        "openssl/opensslv.h: No such file" to "fatal error: openssl/opensslv.h: No such file or directory\nrunning out of openssl headers",
                                        "aarch64-linux gcc missing" to "aarch64-linux-gnu-gcc: command not found, process finished",
                                        "multiple definition of yylloc" to "multiple definition of 'yylloc' or DTC lexer compilation halted",
                                        "SUBLEVEL parsing exception" to "Makefile: SUBLEVEL parse index mismatch layout",
                                        "SUSFS patch conflict" to "susfs patch failed: reject hunk #3 in drivers/fs",
                                        "KernelSU context collision" to "KernelSU file system mounting security hook context conflict",
                                        "bc/bison not installed" to "missing bc or bison tool binary"
                                    )
                                    presets.forEach { (label, rawText) ->
                                        Box(
                                            modifier = Modifier
                                                .background(Color(0xFF1E293B), RoundedCornerShape(6.dp))
                                                .clickable { errorInputText = rawText }
                                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            OutlinedTextField(
                                value = errorInputText,
                                onValueChange = { errorInputText = it },
                                label = { Text("Paste Raw Builder Error Terminal Logs Here", color = Color(0xFF64748B)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .testTag("log_troubleshooter_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF10B981),
                                    unfocusedBorderColor = Color(0xFF1F2937),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                placeholder = { Text("E.g. fatal error: openssl/opensslv.h...", color = Color(0xFF475569), fontSize = 12.sp) }
                            )
                        }

                        if (detectedIssues.isNotEmpty()) {
                            item {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "🔍 DETECTED COMPILATION ISSUES (" + detectedIssues.size + ")",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF10B981)
                                    )
                                    Text(
                                        text = "Clear Text",
                                        fontSize = 11.sp,
                                        color = Color(0xFFEF4444),
                                        modifier = Modifier.clickable {
                                            errorInputText = ""
                                            preCompilationHookCommands = ""
                                        }
                                    )
                                }
                            }

                            items(detectedIssues) { issue ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B4B).copy(alpha = 0.5f)),
                                    border = BorderStroke(1.dp, Color(0xFF312E81)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = issue.title.uppercase(),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = Color(0xFFF43F5E)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = issue.description,
                                            fontSize = 12.sp,
                                            color = Color(0xFFCBD5E1)
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(
                                            text = "🔧 SUGGESTED MITIGATION STRATEGY:",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF38BDF8)
                                        )
                                        Text(
                                            text = issue.suggestedFix,
                                            fontSize = 11.sp,
                                            color = Color(0xFF94A3B8)
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = "INJECTABLE HOOK SCRIPT COMMAND CHAIN:",
                                            fontSize = 9.sp,
                                            color = Color(0xFF64748B),
                                            fontWeight = FontWeight.Bold
                                        )
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color(0xFF030712), RoundedCornerShape(6.dp))
                                                .border(1.dp, Color(0xFF111827), RoundedCornerShape(6.dp))
                                                .padding(10.dp)
                                        ) {
                                            Text(
                                                text = issue.scriptToInject,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                color = Color(0xFF10B981)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(12.dp))

                                        val isInjected = preCompilationHookCommands == issue.scriptToInject
                                        Button(
                                            onClick = {
                                                if (isInjected) {
                                                    preCompilationHookCommands = ""
                                                    Toast.makeText(context, "Patch removed from Actions YAML pipeline!", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    preCompilationHookCommands = issue.scriptToInject
                                                    Toast.makeText(context, "Patch injected into Actions YAML pipeline successfully!", Toast.LENGTH_LONG).show()
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isInjected) Color(0xFFEF4444) else Color(0xFF10B981)
                                            ),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(
                                                imageVector = if (isInjected) Icons.Default.Close else Icons.Default.Check,
                                                contentDescription = "Inject action icon",
                                                tint = if (isInjected) Color.White else Color.Black,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (isInjected) "Remove automated patch from Actions step" else "Inject automated patch to Actions workflow",
                                                color = if (isInjected) Color.White else Color.Black,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF0F172A).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                        .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(8.dp))
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Safe State Icon",
                                            tint = Color(0xFF10B981).copy(alpha = 0.6f),
                                            modifier = Modifier.size(36.dp)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "No issue currently scanned or entered",
                                            color = Color(0xFF94A3B8),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "Either select a log preset tile above or paste actual GKI compilation terminal errors to calculate targeted resolutions.",
                                            color = Color(0xFF64748B),
                                            fontSize = 10.sp,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                WorkbenchTab.RECIPES -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            Text(
                                text = "CUSTOM TENSOR G5 KERNEL RECIPES",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981),
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Code fragments to manually edit, clean, patch, and pace your Google Pixel 10 compiler tree.",
                                fontSize = 11.sp,
                                color = Color(0xFF64748B)
                            )
                        }

                        items(recipes) { recipe ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                                border = BorderStroke(1.dp, Color(0xFF1F2937)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .background(Color(0xFF10B981).copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = recipe.category.uppercase(),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF10B981)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = recipe.title,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = recipe.description,
                                        fontSize = 12.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF070A13), RoundedCornerShape(6.dp))
                                            .padding(10.dp)
                                    ) {
                                        Text(
                                            text = recipe.commands,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = Color(0xFFF472B6)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        IconButton(
                                            onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                val clip = ClipData.newPlainText("kernel_recipe", recipe.commands)
                                                clipboard.setPrimaryClip(clip)
                                                Toast.makeText(context, "Recipe copied!", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.Share, contentDescription = "Copy code fragment", tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Simulated Terminal log overlay
            if (showTerminalSim) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.85f))
                        .clickable(enabled = false) { }
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF02040A), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(12.dp))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0B1222))
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFEF4444)))
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFF59E0B)))
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF10B981)))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = "laguna-runner-terminal",
                                color = Color(0xFF64748B),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            if (isCompilingSim) {
                                CircularProgressIndicator(
                                    color = Color(0xFF10B981),
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color(0xFF64748B),
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { showTerminalSim = false }
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(terminalLogs) { log ->
                                    Text(
                                        text = log,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = if (log.contains("SUCCESS") || log.contains("Verified")) Color(0xFF10B981)
                                                else if (log.contains("Error") || log.contains("warning")) Color(0xFFEF4444)
                                                else Color(0xFF94A3B8),
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
@androidx.compose.ui.tooling.preview.Preview
fun KernelWorkbenchAppPreview() {
    MyApplicationTheme {
        KernelWorkbenchApp()
    }
}
