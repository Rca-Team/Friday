import time
import subprocess
import urllib.parse
try:
    import pyautogui
except ImportError:
    pyautogui = None

def whatsapp_call(parameters=None, response=None, player=None, session_memory=None) -> str:
    contact = (parameters or {}).get("contact", "").strip()
    if not contact:
        return "Please specify a contact to call on WhatsApp, sir."
    
    if player:
        player.write_log(f"[whatsapp_call] Opening chat with {contact}")
    
    if not pyautogui:
        return "PyAutoGUI is required for this feature, sir."
    
    try:
        # Open WhatsApp
        pyautogui.press("win")
        time.sleep(0.5)
        pyautogui.write("WhatsApp", interval=0.05)
        time.sleep(0.5)
        pyautogui.press("enter")
        
        # Wait for WhatsApp to load
        time.sleep(4)
        
        # Open New Chat / Search
        pyautogui.hotkey("ctrl", "n")
        time.sleep(1)
        
        # Type contact name
        pyautogui.write(contact, interval=0.05)
        time.sleep(1.5)
        
        # Select the first result
        pyautogui.press("down")
        time.sleep(0.5)
        pyautogui.press("enter")
        
        return f"I have opened WhatsApp and found {contact}. You can now start the call, sir."
    except Exception as e:
        return f"Failed to automate WhatsApp, sir: {e}"
