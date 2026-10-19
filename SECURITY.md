# Security policy

## Supported versions

Only the current development line is being evaluated. There is no supported production release, long-term support branch, or promised response time.

## Reporting a vulnerability

If this repository offers **Report a vulnerability** under its Security tab, use that private channel. Do not publish credentials, personal data, exploitable server details, or private world files in an ordinary issue.

If private reporting is unavailable, open a minimal issue asking the maintainer for a private reporting channel, without vulnerability details. Do not invent a contact address or send reports to unrelated third-party mod authors.

## Development control boundary

The client-control interface is for a local, isolated development instance. It is not an authenticated remote administration service. Keep its filesystem directories private and do not expose them through a public web server or shared writable directory.

Logs and captures may contain local paths, player identifiers, or world information. Review and redact them before attaching them. Never attach entire dependency caches or game installations.
