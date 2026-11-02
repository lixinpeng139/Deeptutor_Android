"""Add translation keys to every web locale (app.json), in place.

usage: python add_locale_keys.py <web-root>
Keys missing from a locale fall back to English via i18next, but the parity
check wants en and zh to match, so every key here carries both.
"""

import json
from pathlib import Path
import sys

KEYS = {
    "Tap to complete": {
        "en": "Tap to complete", "zh": "轻触补全", "de": "Tippen zum Vervollständigen",
        "fr": "Touchez pour valider", "ja": "タップで補完", "pl": "Dotknij, aby uzupełnić",
        "pt": "Toque para completar", "tr": "Tamamlamak için dokunun", "uk": "Торкніться, щоб завершити",
        "vi": "Chạm để hoàn thành",
    },
}


def main(web: Path) -> None:
    for locale_dir in sorted((web / "locales").iterdir()):
        path = locale_dir / "app.json"
        if not path.is_file():
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        changed = False
        for key, translations in KEYS.items():
            value = translations.get(locale_dir.name)
            if value and data.get(key) != value:
                data[key] = value
                changed = True
        if changed:
            path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
            print("updated", path.relative_to(web))


if __name__ == "__main__":
    main(Path(sys.argv[1]))
