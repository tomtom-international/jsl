/*
 * Copyright (C) 2012-2019, TomTom (http://tomtom.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.nio.file.*;
import static java.nio.file.StandardCopyOption.*;

@NonCPS
def call(String pinnedPluginsStr, boolean savePinnedPluginArchive = false) {
    // Create pinned plugins map from input
    pinnedPlugins = [:]
    pinnedPluginsStr.split(",").each{
        pluginNameVersionArray = it.split(":")
        if (pluginNameVersionArray.size() > 1) {
            pinnedPlugins.put(pluginNameVersionArray[0].trim(), pluginNameVersionArray[1].trim())
        }
    }

    // Generates a list with the exact version of each plugin that is currently installed
    // Allows manual recovery if a plugin upgrade causes problems
    def backup_plugins_list = []
    Jenkins.instance.pluginManager.plugins.each{
        plugin -> 
            backup_plugins_list.push("${plugin.getShortName()}:${plugin.getVersion()}")
    }
    FileWriter plugins_backup_list_file = new FileWriter("$WORKSPACE/plugins-backup.txt");
    plugins_backup_list_file.write(backup_plugins_list.join("\n"));
    plugins_backup_list_file.close();


    // Ensure pinned_plugins directory exists
    if (savePinnedPluginArchive){ 
        File plugin_dir = new File("$WORKSPACE/pinned_plugins");
        plugin_dir.mkdir();
    }

    // Generate a list of plugins, with pinned plugins set to the version provided, and everything else set to latest.
    // If a pinned plugin is missing (regardless of version), an error will be thrown and the list will be saved *without* that pinned plugin
    plugins_list = []
    Jenkins.instance.pluginManager.plugins.each{
    plugin ->
        if (pinnedPlugins.containsKey(plugin.getShortName())) {
            plugins_list.push("${plugin.getShortName()}:${pinnedPlugins[plugin.getShortName()]}") 
            // Write a copy of the plugin to disk
            if (savePinnedPluginArchive){
                Path destination = Paths.get("$WORKSPACE/pinned_plugins/${plugin.getShortName()}.${pinnedPlugins[plugin.getShortName()]}.hpi")
                Files.copy(plugin.archive.toPath(), destination, REPLACE_EXISTING)
            }
        }
        else {
            plugins_list.push("${plugin.getShortName()}:latest")
        }
        pinnedPlugins.remove(plugin.getShortName())
    }

    FileWriter plugins_list_file = new FileWriter("$WORKSPACE/plugins.txt");
    plugins_list_file.write(plugins_list.join("\n"));
    plugins_list_file.close();

    // Check if all the pinnedPlugins have been accounted for, if not throw an exception
    if (pinnedPlugins.size() > 0) {
        throw new Exception("One or more pinned plugins were not installed, they will not be included on the plugins list!")
    }
}