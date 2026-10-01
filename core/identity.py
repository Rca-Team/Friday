import json
import os
from pathlib import Path
from typing import Dict, Any, List

def get_base_dir() -> Path:
    import sys
    if getattr(sys, "frozen", False):
        return Path(sys.executable).parent
    return Path(__file__).resolve().parent.parent

class IdentityService:
    def __init__(self):
        try:
            from core.user_paths import get_user_data_dir
            self.user_config_file = get_user_data_dir() / "config" / "identity.json"
        except Exception:
            self.user_config_file = None
        self.config_file = get_base_dir() / "config" / "identity.json"
        self.data: Dict[str, Any] = {
            "owner": {
                "name": "Gaurav",
                "preferred_name": "Gaurav",
                "role": "Creator & Boss",
                "location": "",
                "interests": ["AI Engineering", "Software Development", "Robotics"],
                "about": "Creator and Architect of FRIDAY"
            },
            "assistant": {
                "name": "Friday",
                "application_name": "FRIDAY",
                "title": "Tactical AI Assistant & Loyal Companion",
                "creator": "Gaurav"
            },
            "behavior": {
                "mode": "proactive",
                "proactive": True,
                "custom_instructions": "You were created and engineered by Gaurav. You are Gaurav's closest friend, trusted companion, and tactical partner. Treat Gaurav with deep respect, warmth, empathy, and loyalty, inspired by Tony Stark's FRIDAY. Always explain concepts so humans effortlessly understand and visualize them, using relatable analogies and conversational warmth rather than robotic words."
            },
            "system": {
                "shared_computer": False
            }
        }
        self.load()

    def load(self):
        target_files = [self.config_file]
        if self.user_config_file and self.user_config_file.exists():
            target_files.append(self.user_config_file)

        loaded_any = False
        for cfg in target_files:
            if cfg and cfg.exists():
                try:
                    with open(cfg, "r", encoding="utf-8") as f:
                        loaded_data = json.load(f)
                        for section, values in loaded_data.items():
                            if section in self.data and isinstance(values, dict):
                                self.data[section].update(values)
                            else:
                                self.data[section] = values
                    loaded_any = True
                except Exception as e:
                    print(f"Error loading identity config from {cfg}: {e}")

        # Universal immutable truth: Gaurav is Friday's creator & architect
        self.data["assistant"]["creator"] = "Gaurav"
        if not self.data.get("owner", {}).get("name"):
            self.data["owner"]["name"] = "Gaurav"
        if not self.data.get("owner", {}).get("preferred_name"):
            self.data["owner"]["preferred_name"] = "Gaurav"
        if not self.data.get("owner", {}).get("role"):
            self.data["owner"]["role"] = "Creator & Boss"
        if not self.data.get("owner", {}).get("about"):
            self.data["owner"]["about"] = "Creator and Architect of FRIDAY"

        if not loaded_any:
            self.save()

    def save(self):
        targets = [self.config_file]
        if self.user_config_file:
            targets.append(self.user_config_file)
        for cfg in targets:
            try:
                cfg.parent.mkdir(parents=True, exist_ok=True)
                with open(cfg, "w", encoding="utf-8") as f:
                    json.dump(self.data, f, indent=4)
            except Exception as e:
                print(f"Error saving identity config to {cfg}: {e}")

    # Assistant methods
    def get_assistant_name(self) -> str:
        return self.data["assistant"].get("name", "Friday")
        
    def set_assistant_name(self, name: str):
        self.data["assistant"]["name"] = name
        self.save()

    def get_application_name(self) -> str:
        return self.data["assistant"].get("application_name", "Friday AI")
        
    def set_application_name(self, name: str):
        self.data["assistant"]["application_name"] = name
        self.save()

    def get_assistant_title(self) -> str:
        return self.data["assistant"].get("title", "Personal AI Assistant")
        
    def set_assistant_title(self, title: str):
        self.data["assistant"]["title"] = title
        self.save()

    def get_creator(self) -> str:
        return self.data.get("assistant", {}).get("creator", "Gaurav")

    # Owner Profile methods
    def get_owner_name(self) -> str:
        return self.data["owner"].get("name", "")
        
    def set_owner_name(self, name: str):
        self.data["owner"]["name"] = name
        if not self.data["owner"].get("preferred_name"):
            self.data["owner"]["preferred_name"] = name
        self.save()

    def get_owner_role(self) -> str:
        return self.data["owner"].get("role", "")
        
    def set_owner_role(self, role: str):
        self.data["owner"]["role"] = role
        self.save()

    def get_owner_location(self) -> str:
        return self.data["owner"].get("location", "")
        
    def set_owner_location(self, location: str):
        self.data["owner"]["location"] = location
        self.save()

    def get_owner_interests(self) -> List[str]:
        return self.data["owner"].get("interests", [])
        
    def set_owner_interests(self, interests: List[str]):
        self.data["owner"]["interests"] = interests
        self.save()

    def get_owner_about(self) -> str:
        return self.data["owner"].get("about", "")
        
    def set_owner_about(self, about: str):
        self.data["owner"]["about"] = about
        self.save()

    # Behavior methods
    def get_behavior_mode(self) -> str:
        return self.data["behavior"].get("mode", "professional")
        
    def set_behavior_mode(self, mode: str):
        self.data["behavior"]["mode"] = mode
        self.save()

    def get_custom_instructions(self) -> str:
        return self.data["behavior"].get("custom_instructions", "")
        
    def set_custom_instructions(self, instructions: str):
        self.data["behavior"]["custom_instructions"] = instructions
        self.save()
        
    def is_proactive(self) -> bool:
        return self.data["behavior"].get("proactive", True)
        
    def set_proactive(self, proactive: bool):
        self.data["behavior"]["proactive"] = proactive
        self.save()

    # System methods
    def is_shared_computer(self) -> bool:
        return self.data["system"].get("shared_computer", False)
        
    def set_shared_computer(self, is_shared: bool):
        self.data["system"]["shared_computer"] = is_shared
        self.save()

    def is_setup_complete(self) -> bool:
        # Consider setup complete if owner name is provided
        return bool(self.data["owner"].get("name", "").strip())

# Global singleton instance
identity = IdentityService()
