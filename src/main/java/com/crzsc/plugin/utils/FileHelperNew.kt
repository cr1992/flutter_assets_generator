package com.crzsc.plugin.utils

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessModuleDir
import com.intellij.openapi.vfs.VirtualFile
import io.flutter.pub.PubRoot
import io.flutter.utils.FlutterModuleUtils
import org.jetbrains.kotlin.idea.util.projectStructure.allModules
import org.jetbrains.kotlin.konan.file.File
import org.yaml.snakeyaml.Yaml
import java.io.FileInputStream
import java.util.*
import java.util.regex.Pattern
import kotlin.collections.ArrayList

/**
 * 基于Module来处理Assets
 */
object FileHelperNew {

    /**
     * 获取所有可用的Flutter Module的Asset配置
     */
    @JvmStatic
    fun getAssets(project: Project): List<ModulePubSpecConfig> {
        val modules = project.allModules()
        val folders = mutableListOf<ModulePubSpecConfig>()
        for (module in modules) {
            if (FlutterModuleUtils.isFlutterModule(module)) {
                val moduleDir = module.guessModuleDir()
                if (moduleDir != null) {
                    getPubSpecConfig(module)?.let {
                        folders.add(it)
                    }
                }
            }
        }
        return folders
    }

    @JvmStatic
    fun shouldActivateFor(project: Project): Boolean {
        return FlutterModuleUtils.hasFlutterModule(project)
    }

    fun tryGetAssetsList(map: Map<*, *>) : MutableList<*>? {
        (map["flutter"] as? Map<*, *>)?.let {
            return it["assets"] as? MutableList<*>
        }
        return null
    }

    @JvmStatic
    fun getPubSpecConfig(module: Module): ModulePubSpecConfig? {
        try {
            val moduleDir = module.guessModuleDir()
            val pubRoot = PubRoot.forDirectory(moduleDir)
            if (moduleDir != null && pubRoot != null) {
                val fis = FileInputStream(pubRoot.pubspec.path)
                val pubConfigMap = Yaml().load(fis) as? Map<String, Any>
                if (pubConfigMap != null) {
                    val assetVFiles = mutableListOf<VirtualFile>()
                    (pubConfigMap["flutter"] as? Map<*, *>)?.let { configureMap ->
                        (configureMap["assets"] as? ArrayList<*>)?.let { list ->
                            for (path in list) {
                                moduleDir.findFileByRelativePath(path as String)?.let {
                                    if (it.isDirectory) {
                                        val index = path.indexOf("/")
                                        val assetsPath = if (index == -1) {
                                            path
                                        } else {
                                            path.substring(0, index)
                                        }
                                        val assetVFile = moduleDir.findChild(assetsPath)
                                            ?: moduleDir.createChildDirectory(this, assetsPath)
                                        if (!assetVFiles.contains(assetVFile)) {
                                            assetVFiles.add(assetVFile)
                                        }
                                    } else {
                                        if (!assetVFiles.contains(it)) {
                                            assetVFiles.add(it)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    return ModulePubSpecConfig(
                        module,
                        pubRoot,
                        assetVFiles,
                        pubConfigMap,
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
        return null
    }

    /**
     * 读取配置
     */
    private fun readSetting(config: ModulePubSpecConfig, key: String): Any? {
        (config.map[Constants.KEY_CONFIGURATION_MAP] as? Map<*, *>)?.let { configureMap ->
            return configureMap[key]
        }
        return null
    }

    /**
     * 是否开启了自动检测
     */
    fun isAutoDetectionEnable(config: ModulePubSpecConfig): Boolean {
        return readSetting(config, Constants.KEY_AUTO_DETECTION) as Boolean? ?: true
    }

    /**
     * 是否根据父文件夹命名 默认true
     */
    fun isNamedWithParent(config: ModulePubSpecConfig): Boolean {
        return readSetting(config, Constants.KEY_NAMED_WITH_PARENT) as Boolean? ?: true
    }

    /**
     * 读取生成的类名配置
     */
    fun getGeneratedClassName(config: ModulePubSpecConfig): String {
        return readSetting(config, Constants.KEY_CLASS_NAME) as String? ?: Constants.DEFAULT_CLASS_NAME
    }

    /**
     * 读取文件分割配置
     */
    fun getFilenameSplitPattern(config: ModulePubSpecConfig): String {
        return try {
            val pattern =
                readSetting(config, Constants.FILENAME_SPLIT_PATTERN) as String?
                    ?: Constants.DEFAULT_FILENAME_SPLIT_PATTERN
            Pattern.compile(pattern)
            pattern
        } catch (e: Exception) {
            e.printStackTrace()
            Constants.DEFAULT_FILENAME_SPLIT_PATTERN
        }
    }

    /**
     * 读取忽略文件目录
     */
    fun getPathIgnore(config: ModulePubSpecConfig): List<String> {
        return try {
            val paths =
                readSetting(config, Constants.PATH_IGNORE) as List<String>?
                    ?: emptyList()
            paths
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 获取generated自动生成目录
     * 从yaml中读取
     */
    private fun getGeneratedFilePath(config: ModulePubSpecConfig): VirtualFile {
        return config.pubRoot.lib?.let { lib ->
            // 没有配置则返回默认path
            val filePath: String = readSetting(config, Constants.KEY_OUTPUT_DIR) as String?
                ?: return@let lib.findOrCreateChildDir(lib, Constants.DEFAULT_OUTPUT_DIR)
            if (!filePath.contains(File.separator)) {
                return@let lib.findOrCreateChildDir(lib, filePath)
            } else {
                var file = lib
                filePath.split(File.separator).forEach { dir ->
                    if (dir.isNotEmpty()) {
                        file = file.findOrCreateChildDir(file, dir)
                    }
                }
                return@let file
            }
        }!!
    }

    private fun VirtualFile.findOrCreateChildDir(requestor: Any, name: String): VirtualFile {
        val child = findChild(name)
        return child ?: createChildDirectory(requestor, name)
    }

    fun getGeneratedFile(config: ModulePubSpecConfig): VirtualFile {
        return getGeneratedFilePath(config).let {
            val configName = readSetting(config, Constants.KEY_OUTPUT_FILENAME)
            return@let it.findOrCreateChildData(
                it,
                "${configName ?: Constants.DEFAULT_CLASS_NAME.lowercase(Locale.getDefault())}.dart"
            )
        }
    }

}

/**
 * 模块Flutter配置信息
 */
data class ModulePubSpecConfig(
    val module: Module,
    val pubRoot: PubRoot,
    val assetVFiles: List<VirtualFile>,
    val map: Map<String, Any>,
    val isFlutterModule: Boolean = FlutterModuleUtils.isFlutterModule(module)
)