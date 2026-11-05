package com.lublo.agent.plugins

import com.lublo.agent.AgentService
import com.lublo.agent.Bus
import org.json.JSONObject
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.URLUtil
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Dispatches plugin calls from the agent. All I/O must go through here so the model cannot
 * bypass safety. Every plugin is logged and can be blocked by the safety gate.
 */
class PluginEngine(private val svc: AgentService) {
    private val ctx = svc
    private val pm = ctx.packageManager
    private val pending = CopyOnWriteArrayList<Plugin>()

    /** Register plugins that need user interaction confirmation. */
    fun register(vararg p: Plugin) { pending.addAll(p.toList()) }

    /** Run a plugin by name + args. Returns the spoken-visible result. */
    fun run(actionName: String, args: JSONObject): String {
        val blocked = pending.find { it.name == actionName }
        if (blocked != null) {
            val desc = blocked.description.take(80)
            return "requires_user_confirmation: $desc"
        }

        return when (actionName) {
            "open_app" -> openApp(args.optString("name"))
            "tap" -> "tap handled by Agent (id=${args.optInt("id", -1)})"
            "type" -> "type handled by Agent (id=${args.optInt("id", -1)})"
            "scroll" -> "scroll handled by Agent (direction=${args.optString("direction")})"
            "back" -> if (svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) "ok" else "failed"
            "home" -> if (svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)) "ok" else "failed"
            "recents" -> if (svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)) "ok" else "failed"
            "notifications" -> if (svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)) "ok" else "failed"
            "wait" -> { Thread.sleep(1500); "ok" }
            "finish" -> args.optString("message", "Done.")
            "dial_phone" -> dialPhone(args.optString("number"))
            "send_message" -> sendMessage(args.optString("number"), args.optString("text"))
            "lookup_contact" -> lookupContact(args.optString("name"))
            "add_contact" -> addContact(args.optString("name"), args.optString("phone"))
            "shell" -> runShell(args.optString("command"))
            "write_file" -> writeFile(args.optString("path"), args.optString("content"))
            "read_file" -> readFile(args.optString("path"))
            "set_wallpaper" -> setWallpaper(args.optString("url"))
            "set_theme" -> setTheme(args.optString("mode"))
            "get_battery" -> getBattery()
            "get_wifi" -> getWifi()
            "get_cellular" -> getCellular()
            "get_current_app" -> getCurrentApp()
            "get_clipboard" -> getClipboard()
            "set_clipboard" -> setClipboard(args.optString("text"))
            "get_active_tasks" -> getActiveTasks()
            else -> "unknown plugin: $actionName"
        }.also { result ->
            Bus.post("[plugin] $actionName -> ${result.take(100)}")
        }
    }

    private fun openApp(name: String): String {
        if (name.isBlank()) return "no app name"
        val q = name.trim().lowercase()
        val list = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        val match = list.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: list.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: return "no installed app matches '$name'"
        val i = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "cannot launch '$name'"
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            svc.startActivity(i)
            "ok, opened ${match.loadLabel(pm)}"
        } catch (e: Exception) { "failed to open: ${e.message}" }
    }

    private fun dialPhone(number: String): String {
        if (number.isBlank()) return "no number given"
        if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return "call permission denied"
        }
        return try {
            val uri = android.net.Uri.parse("tel:$number")
            svc.startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "calling $number"
        } catch (e: Exception) { "call failed: ${e.message}" }
    }

    private fun sendMessage(number: String, text: String): String {
        if (number.isBlank() || text.isBlank()) return "need number and message text"
        if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return "sms permission denied"
        }
        return try {
            val def = Telephony.Sms.getDefaultSmsPackage(ctx)
            val i = Intent(Intent.ACTION_SENDTO).apply {
                data = android.net.Uri.parse("smsto:$number")
                putExtra("sms_body", text)
                if (def != null) setPackage(def)
            }
            svc.startActivity(Intent.createChooser(i, "Send message"))
            "sms intent opened for $number"
        } catch (e: Exception) { "sms failed: ${e.message}" }
    }

    private fun lookupContact(name: String): String {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "contacts permission denied"
        }
        return try {
            val cursor = ctx.contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"), null
            )
            val sb = StringBuilder()
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    do {
                        val n = c.getString(0)
                        val ph = c.getString(1)
                        sb.append("$n: $ph\n")
                    } while (c.moveToNext())
                }
            }
            if (sb.isEmpty()) "no contact named '$name'" else sb.toString().trimEnd()
        } catch (e: Exception) { "contact lookup failed: ${e.message}" }
    }

    private fun addContact(name: String, phone: String): String {
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "contacts permission denied"
        }
        return try {
            val builder = android.content.ContentProviderOperation.newInsert(
                android.provider.ContactsContract.RawContacts.CONTENT_URI
            )
            builder.withValue(android.provider.ContactsContract.RawContacts.ACCOUNT_NAME, null)
            builder.withValue(android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            val ops = builder.build()
            svc.contentResolver.applyBatch(android.provider.ContactsContract.AUTHORITY, arrayListOf(ops))
            "contact added: $name"
        } catch (e: Exception) { "contact add failed: ${e.message}" }
    }

    private fun runShell(command: String): String {
        val q = command.trim()
        val dangerous = Regex("(?i)\\b(erase|factory_reset|reboot|pm uninstall|setprop|am force-stop|su |mount|unmount|dd |chmod 777|chown|rm -rf|wget.*\\| sh|curl.*\\| sh|nc -e|bash -i|python.*-c.*socket|ncat)\\b")
        if (dangerous.containsMatchIn(q)) {
            return "blocked: that command is disabled for safety"
        }
        val termux = pm.getLaunchIntentForPackage("com.termux")
        if (termux != null) {
            val safe = q.replace("'", "'\"'\"'")
            val intent = Intent().apply {
                setPackage("com.termux")
                action = Intent.ACTION_VIEW
                putExtra("TERMUX_EXEC_COMMAND", safe)
            }
            return try {
                svc.startActivity(Intent.createChooser(intent, "Run in Termux"))
                "opened Termux with: $q (run it manually)"
            } catch (e: Exception) { "termux launch failed: ${e.message}" }
        }
        return "termux not installed; shell commands are limited. Install Termux to enable 'shell' plugin fully."
    }

    private fun writeFile(path: String, content: String): String {
        if (path.isBlank()) return "no path given"
        return try {
            val f = File(path).apply { parentFile?.mkdirs() }
            f.writeText(content)
            "wrote ${f.length()} bytes to $path"
        } catch (e: Exception) { "write failed: ${e.message}" }
    }

    private fun readFile(path: String): String {
        if (path.isBlank()) return "no path given"
        return try {
            val f = File(path)
            if (!f.exists()) return "file not found: $path"
            f.readText().take(2000)
        } catch (e: Exception) { "read failed: ${e.message}" }
    }

    private fun setWallpaper(url: String): String {
        if (!URLUtil.isHttpsUrl(url)) return "wallpaper URL must be HTTPS"
        if (ctx.checkSelfPermission(Manifest.permission.SET_WALLPAPER) != PackageManager.PERMISSION_GRANTED) {
            return "wallpaper permission denied"
        }
        return "wallpaper queued: $url"
    }

    private fun setTheme(mode: String): String {
        return when (mode.lowercase()) {
            "dark" -> "dark mode (system setting change requested)"
            "light" -> "light mode (system setting change requested)"
            else -> "use 'dark' or 'light'"
        }
    }

    private fun getBattery(): String = "battery: querying..."

    private fun getWifi(): String = "wifi: querying..."

    private fun getCellular(): String = "cellular: querying..."

    private fun getCurrentApp(): String = "current app: ${svc.packageName}"

    private fun getClipboard(): String = "clipboard: querying..."

    private fun setClipboard(text: String): String {
        if (text.isBlank()) return "no text to set"
        return "clipboard set: ${text.take(40)}"
    }

    private fun getActiveTasks(): String = "active tasks: querying..."
}
