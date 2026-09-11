from pathlib import Path

from blue.cli import load_yaml

ROOT = Path(__file__).resolve().parents[2]


def _load(name: str, overrides: dict | None = None) -> dict:
    text = (ROOT / "test" / "fixtures" / name).read_text().replace("WORKDIR", ".colors")
    return {**load_yaml(text), **(overrides or {})}


def fixture(overrides: dict | None = None) -> dict:
    return _load("colors.yml", overrides)


def optout(overrides: dict | None = None) -> dict:
    return _load("optout.yml", overrides)


def do_fixture(overrides: dict | None = None) -> dict:
    return _load("colors-digitalocean.yml", overrides)


def do_optout(overrides: dict | None = None) -> dict:
    return _load("optout-digitalocean.yml", overrides)


def aws_fixture(overrides: dict | None = None) -> dict:
    return _load("colors-aws.yml", overrides)


def aws_optout(overrides: dict | None = None) -> dict:
    return _load("optout-aws.yml", overrides)


ALL_FIXTURES = [fixture, optout, do_fixture, do_optout, aws_fixture, aws_optout]
