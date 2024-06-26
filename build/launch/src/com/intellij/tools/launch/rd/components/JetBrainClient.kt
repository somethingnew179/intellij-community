package com.intellij.tools.launch.rd.components

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.tools.launch.PathsProvider
import com.intellij.tools.launch.ide.IdeDebugOptions
import com.intellij.tools.launch.ide.IdeLaunchContext
import com.intellij.tools.launch.ide.IdeLauncher
import com.intellij.tools.launch.ide.classpathCollector
import com.intellij.tools.launch.ide.environments.local.LocalIdeCommandLauncherFactory
import com.intellij.tools.launch.ide.environments.local.LocalProcessLaunchResult
import com.intellij.tools.launch.ide.environments.local.localLaunchOptions
import com.intellij.tools.launch.os.ProcessOutputStrategy
import kotlinx.coroutines.CoroutineScope
import java.io.File

data class JetBrainsClientLaunchResult(
  val localProcessLaunchResult: LocalProcessLaunchResult,
  val debugPort: Int,
)

fun runJetBrainsClientLocally(clientProcessLifespanScope: CoroutineScope): JetBrainsClientLaunchResult {
  JetBrainClient.logger.info("Starting JetBrains client")
  val paths = JetBrainsClientIdeaPathsProvider()
  val classpath = classpathCollector(
    paths,
    mainModule = RemoteDevConstants.INTELLIJ_CWM_GUEST_MAIN_MODULE,
    additionalRuntimeModules = listOf(RemoteDevConstants.GATEWAY_PLUGIN_MODULE)
  )
  val debugPort = 5007
  val localProcessLaunchResult = IdeLauncher.launchCommand(
    LocalIdeCommandLauncherFactory(localLaunchOptions(
      processOutputStrategy = ProcessOutputStrategy.Pipe,
      processTitle = "JetBrains Client",
      lifespanScope = clientProcessLifespanScope
    )),
    context = IdeLaunchContext(
      classpathCollector = classpath,
      // changed in Java 9, now we have to use *: to listen on all interfaces
      localPaths = paths,
      ideDebugOptions = IdeDebugOptions(debugPort, debugSuspendOnStart = true, bindToHost = ""),
      platformPrefix = RemoteDevConstants.JETBRAINS_CLIENT_PREFIX,
      ideaArguments = listOf("thinClient", "debug://localhost:5990#newUi=true"),
      environment = mapOf(
        "CWM_NO_TIMEOUTS" to "1",
        "CWM_CLIENT_PASSWORD" to RemoteDevConstants.DEFAULT_CWM_PASSWORD,
      ),
      specifyUserHomeExplicitly = false,
    )
  )
  return JetBrainsClientLaunchResult(localProcessLaunchResult, debugPort)
}

private class JetBrainsClientIdeaPathsProvider : PathsProvider {
  override val productId: String
    get() = RemoteDevConstants.JETBRAINS_CLIENT_PREFIX
  override val sourcesRootFolder: File
    get() = File(PathManager.getHomePath())
  override val communityRootFolder: File
    get() = sourcesRootFolder.resolve("community")
  override val outputRootFolder: File
    get() = sourcesRootFolder.resolve("out").resolve("classes")
}

private object JetBrainClient {
  val logger = logger<JetBrainClient>()
}