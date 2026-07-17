#!/usr/bin/env python3
# coding: utf-8

from cloudscraper import CloudScraper
from urllib.parse import urlparse
import logging
import os
import re


logging.basicConfig(
    level=logging.INFO,
    format="[%(levelname)s] %(message)s",
)
logger = logging.getLogger(__name__)


class MainUrlUpdater:
    def __init__(self, base_dir="."):
        self.base_dir = base_dir
        self.session = CloudScraper()
        self.blacklist = {
            "instapro.ac",
        }

    @property
    def extensions(self):
        # Lists extension directories
        try:
            candidates = [
                file_name
                for file_name in os.listdir(self.base_dir)
                if os.path.isdir(os.path.join(self.base_dir, file_name))
                and not file_name.startswith(".")
                and file_name
                not in {
                    "gradle",
                    "__Temel",
                    "HQPorner",
                    "xVideos",
                    "PornHub",
                    "Xhamster",
                    "Chatrubate",
                }
            ]
            return sorted(candidates)
        except FileNotFoundError:
            return []

    def _find_kt_file(self, directory, file_name):
        # Searches for a .kt file inside the directory
        start_path = os.path.join(self.base_dir, directory)

        for root, _subdirectories, files in os.walk(start_path):
            if file_name in files:
                return os.path.join(root, file_name)

        return None

    @property
    def kt_files(self):
        # Collects the paths of the main extension files
        result = []

        for extension in self.extensions:
            kt_path = self._find_kt_file(
                extension,
                f"{extension}.kt",
            )

            if kt_path:
                result.append(kt_path)

        return result

    def _find_main_url(self, kt_file_path):
        # Extracts the mainUrl value from the file
        try:
            with open(kt_file_path, "r", encoding="utf-8") as file:
                content = file.read()

                match = re.search(
                    r'override\s+var\s+mainUrl\s*=\s*["\']([^"\']+)["\']',
                    content,
                )

                if match:
                    return match.group(1)

        except Exception:
            logger.error(
                "Error while reading file: %s",
                kt_file_path,
            )

        return None

    def _update_main_url(self, kt_file_path, old_url, new_url):
        # Updates the mainUrl assignment
        try:
            with open(kt_file_path, "r+", encoding="utf-8") as file:
                content = file.read()

                new_content, replacement_count = re.subn(
                    r'(override\s+var\s+mainUrl\s*=\s*["\'])([^"\']+)(["\'])',
                    r"\1" + new_url + r"\3",
                    content,
                    flags=re.IGNORECASE,
                )

                if replacement_count == 0:
                    new_content = content.replace(
                        old_url,
                        new_url,
                    )

                if new_content == content:
                    return False

                file.seek(0)
                file.write(new_content)
                file.truncate()

            return True

        except Exception:
            return False

    def _update_gradle(self, build_gradle_path, new_url):
        # Increments the version and updates the domain inside iconUrl
        try:
            new_domain = self._extract_domain(new_url)

            with open(build_gradle_path, "r+", encoding="utf-8") as file:
                content = file.read()

                # Increment the version
                version_match = re.search(
                    r"(^\s*version\s*=\s*)(\d+)",
                    content,
                    flags=re.MULTILINE,
                )

                if version_match:
                    old_version = int(version_match.group(2))
                    new_version = old_version + 1

                    content = content.replace(
                        version_match.group(0),
                        f"{version_match.group(1)}{new_version}",
                    )
                else:
                    new_version = None

                # Update the domain inside iconUrl's favicon parameter
                # Captures the url=https://... section and replaces it
                # with the new domain
                domain_without_scheme = (
                    new_domain
                    .replace("https://", "")
                    .replace("http://", "")
                )

                content = re.sub(
                    r"(url=https?://)([^&\"\s]+)",
                    r"\1" + domain_without_scheme,
                    content,
                )

                file.seek(0)
                file.write(content)
                file.truncate()

                return new_version

        except Exception:
            return None

    def _extract_domain(self, url, prefer_https=True):
        # Extracts a clean domain from the URL
        if not url:
            return None

        try:
            parsed = urlparse(
                url if "://" in url else f"http://{url}"
            )

            scheme = "https" if prefer_https else parsed.scheme
            netloc = parsed.netloc

            if netloc.startswith("www."):
                netloc = netloc[4:]

            return f"{scheme}://{netloc}"

        except Exception:
            return None

    @property
    def main_url_list(self):
        # Lists files that contain a valid mainUrl
        result = {}

        for kt_file_path in self.kt_files:
            main_url = self._find_main_url(kt_file_path)

            if main_url:
                result[kt_file_path] = main_url

        return result

    def update(self):
        for file_path, main_url in self.main_url_list.items():
            try:
                relative_path = os.path.relpath(
                    file_path,
                    self.base_dir,
                )
                extension_name = relative_path.split(os.sep)[0]

            except Exception:
                continue

            logger.info(
                "[~] Checking: %s",
                extension_name,
            )

            clean_main_url = self._extract_domain(main_url)

            if not clean_main_url:
                continue

            try:
                response = self.session.get(
                    clean_main_url,
                    allow_redirects=True,
                    timeout=15,
                )
                final_url = response.url.rstrip("/")

            except Exception:
                logger.warning(
                    "[!] Connection error: %s",
                    clean_main_url,
                )
                continue

            new_domain = self._extract_domain(final_url)

            if not new_domain or clean_main_url == new_domain:
                continue

            parsed_new_domain = urlparse(new_domain)
            hostname = parsed_new_domain.netloc.lower()

            normalized_hostname = (
                hostname[4:]
                if hostname.startswith("www.")
                else hostname
            )

            if normalized_hostname in self.blacklist:
                logger.info(
                    "[-] Skipped because it is blacklisted: %s",
                    new_domain,
                )
                continue

            # Perform the updates
            if self._update_main_url(
                file_path,
                main_url,
                new_domain,
            ):
                gradle_path = os.path.join(
                    self.base_dir,
                    extension_name,
                    "build.gradle.kts",
                )

                new_version = self._update_gradle(
                    gradle_path,
                    new_domain,
                )

                logger.info(
                    "[»] %s -> %s (v%s)",
                    main_url,
                    new_domain,
                    new_version if new_version else "?",
                )


if __name__ == "__main__":
    updater = MainUrlUpdater(base_dir=".")
    updater.update()