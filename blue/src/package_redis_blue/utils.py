# Bump on any change a launcher pinned to an older commit could not survive.
CONTRACT = 1


def clj_str(value) -> str:
    """Clojure's `str`: nil renders empty, booleans lowercase."""
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)
