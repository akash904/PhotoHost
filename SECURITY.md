# Security policy

PhotoHost serves people's photo libraries, so security reports are taken seriously and handled
before anything else.

## Reporting a vulnerability

**Please do not open a public issue.** Report it privately through GitHub: on this repository's
**Security** tab, choose **Report a vulnerability**. Include what you found, how to reproduce it, and
what an attacker could do with it.

You will get a reply within a week. Fixes are released as soon as they are ready, and you will be
credited in the release notes unless you prefer not to be.

## Supported versions

Only the latest release receives security fixes.

## What is in scope

Anything that lets someone read, change or delete photos, or act as the library, without the
library's token; for example:

- bypassing token checks, or learning the token from the network, logs or URLs;
- weaknesses in the TLS setup or certificate pinning;
- reading or writing files outside the library folder;
- upload, import or free-up-space flaws that can corrupt or lose photos;
- cross-site scripting or request forgery in the web page.

Out of scope: attacks that need the token already, or physical access to an unlocked device, and
the plain-HTTP port being readable on your own network, which is documented behaviour.
