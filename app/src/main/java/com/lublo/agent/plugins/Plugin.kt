package com.lublo.agent.plugins

import com.lublo.agent.AgentService
import org.json.JSONObject

/**
 * Plugin = a capability the agent can invoke. Each plugin is a function the LLM may call
 * via its JSON action. Plugins are typed so the model gets proper schema guidance.
 */
sealed class Plugin(val name: String, val description: String, val args: JSONObject)

// ---------- Core device actions ----------
object OpenApp : Plugin(
    "open_app",
    "Open a specific app by its display name (e.g. 'Settings', 'Chrome', 'WhatsApp').",
    JSONObject().put("name", "Settings")
)

object Tap : Plugin(
    "tap",
    "Tap a UI element identified by its index in the current screen tree.",
    JSONObject().put("id", 0)
)

object Type : Plugin(
    "type",
    "Type text into the currently focused input field, or into field N if given.",
    JSONObject().put("id", 0).put("text", "hello").put("submit", false)
)

object Scroll : Plugin(
    "scroll",
    "Scroll a scrollable element. direction: down/up/left/right.",
    JSONObject().put("direction", "down").put("id", 0)
)

object Back : Plugin("back", "Go back to the previous screen.", JSONObject())
object Home : Plugin("home", "Go to the home screen.", JSONObject())
object Recents : Plugin("recents", "Open recent apps.", JSONObject())
object Notifications : Plugin("notifications", "Open the notification shade.", JSONObject())
object Wait : Plugin("wait", "Wait 1.5 seconds for the screen to settle.", JSONObject())
object Finish : Plugin("finish", "Task complete. Provide a short result message.", JSONObject().put("message", "Done."))

// ---------- Internet & tools ----------
object DialPhone : Plugin(
    "dial_phone",
    "Place a phone call to a number. Ask the user for the number if not given.",
    JSONObject().put("number", "+1234567890")
)

object SendMessage : Plugin(
    "send_message",
    "Send an SMS/text message to a contact or phone number. Provide number and text.",
    JSONObject().put("number", "+1234567890").put("text", "Hello")
)

object LookUpContact : Plugin(
    "lookup_contact",
    "Find a contact by name and return their phone number from the device address book.",
    JSONObject().put("name", "Alice")
)

object AddContact : Plugin(
    "add_contact",
    "Add a new contact to the device with name and phone number.",
    JSONObject().put("name", "Alice").put("phone", "+1234567890")
)

object Shell : Plugin(
    "shell",
    "Run a shell/command line command. Prefer Termux commands when possible. Returns stdout.",
    JSONObject().put("command", "echo hello")
)

object WriteFile : Plugin(
    "write_file",
    "Write text content to a file on the device. Path can be any writable location.",
    JSONObject().put("path", "/sdcard/Documents/notes.txt").put("content", "hello")
)

object ReadFile : Plugin(
    "read_file",
    "Read the contents of a text file from the device.",
    JSONObject().put("path", "/sdcard/Documents/notes.txt")
)

object SetWallpaper : Plugin(
    "set_wallpaper",
    "Set the device wallpaper from a publicly available image URL (must be HTTPS image).",
    JSONObject().put("url", "https://example.com/image.jpg")
)

object SetTheme : Plugin(
    "set_theme",
    "Change device theme/appearance (light or dark mode).",
    JSONObject().put("mode", "dark")
)

// ---------- Context ----------
object GetBattery : Plugin("get_battery", "Return current battery level and charging status.", JSONObject())
object GetWifi : Plugin("get_wifi", "Return current Wi-Fi network name and whether Wi-Fi is on.", JSONObject())
object GetCellular : Plugin("get_cellular", "Return current cellular data and mobile network type.", JSONObject())
object GetCurrentApp : Plugin("get_current_app", "Return the package name of the currently visible app.", JSONObject())
object GetClipboard : Plugin("get_clipboard", "Return the current clipboard text.", JSONObject())
object SetClipboard : Plugin("set_clipboard", "Set text to the device clipboard.", JSONObject().put("text", "clipboard text"))
object GetActiveTasks : Plugin("get_active_tasks", "Return a list of recent/app names currently running.", JSONObject())
