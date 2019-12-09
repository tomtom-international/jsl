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

import jenkins.model.*
import java.nio.file.*;
import static java.nio.file.StandardCopyOption.*;

@NonCPS
def call(String pluginsStr, boolean restart = false) {
    // Derived from https://github.com/blacklabelops-legacy/jenkins/blob/master/imagescripts/initplugins.sh#L22
    def installed = false
    def initialized = false
    def plugins = pluginsStr.split("\n")
    def instance = Jenkins.getInstance()
    def pm = instance.getPluginManager()
    def uc = instance.getUpdateCenter()
    uc.updateAllSites()

    // Generates a list with the exact version of each plugin that is currently installed
    def currentPlugins = [:]
    pm.plugins.each{
        plugin -> 
            currentPlugins.put(plugin.getShortName(),plugin.getVersion())
    }

    boolean pinned_plugin_failure = false

    plugins.each {
        plugin = it.split(":")
        plugin_name = plugin[0]
        list_plugin_version = plugin[1]
        
        plugin_backup_archive_name = "${plugin_name}.${list_plugin_version}.hpi"
        Path backup_plugin_path = Paths.get("$WORKSPACE/pinned_plugins/$plugin_backup_archive_name")

        // Handle pinned plugins
        if (list_plugin_version != "latest") {
            echo(plugin_name + " is pinned.")
            if (list_plugin_version != currentPlugins.get(plugin_name)) {
                echo(plugin_name + " is at version " + currentPlugins[plugin_name] + " but it should be at version " + list_plugin_version)

                if (currentPlugins.get(plugin_name) && Jenkins.instance.pluginManager.getPlugin(plugin_name).getBackupVersion() == list_plugin_version) {
                    echo("Jenkins' backup plugin version matches pinned version, automatically performing downgrade.")
                    uc.getPlugin(plugin_name).doDowngrade()
                    installed = true
                }
                else if (Files.exists(backup_plugin_path)) {
                    echo("Our backup (in ./plugins/) matches, performing installation.")
                    // Not a typo, its really doDoUninstall https://github.com/jenkinsci/jenkins/blob/ead55ac41d7a06cca09fcc59cbbf1bfc8c0f81f7/core/src/main/java/hudson/PluginWrapper.java#L1254
                    pm.getPlugin(plugin_name).doDoUninstall() 
                    Path plugins_loc = Paths.get("$JENKINS_HOME/plugins/$plugin_backup_archive_name")
                    Files.copy(backup_plugin_path, plugins_loc, REPLACE_EXISTING)
                    installed = true
                }
                else {
                    echo("Pinned plugin is not available as a backup. It will need to be handled manually.")
                    pinned_plugin_failure = true
                }
            }
            return
        }

        echo("\nChecking " + plugin_name)
        def plugin = uc.getPlugin(plugin_name)
        if (!plugin) {
            echo(plugin_name + " was not found on the update site.")
            return
        }

        if (plugin.version == currentPlugins[plugin_name]){
            echo(plugin_name + " is already at version " + list_plugin_version)
            return
        }

        if (plugin) {
        echo("Installing " + plugin_name)
            def installFuture = plugin.deploy()
        while(!installFuture.isDone()) {
            echo("Waiting for plugin install: " + plugin_name)
            sleep(3000)
        }
        installed = true
        }
    }

    if (installed && restart) {
    echo("Plugins installed, initializing a restart!")
        instance.save()
        instance.doSafeRestart()
    }

    if (pinned_plugin_failure) {
        throw new Exception("One or more pinned plugins were not installed!")
    }
}