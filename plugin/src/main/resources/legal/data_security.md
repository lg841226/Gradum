**Gradum Data Security and Privacy Statement**

**Effective Date:** August 9, 2026
**Version:** 1.0

**1. Scope and Application**

This Data Security and Privacy Statement ("Statement") governs the
handling of all data, code, and metadata by the Gradum plugin
("Plugin") across all supported integrated development environments
(IDEs) and platforms. By installing, accessing, or using the Plugin,
you ("User") acknowledge that you have read, understood, and agree to
be bound by the terms of this Statement. If you do not agree with any
provision of this Statement, you must immediately cease using the
Plugin and uninstall it from all your devices.

**2. Local-First Architecture and Zero Network Transmission**

Gradum is engineered with a strict "local-first" privacy paradigm.
All code analysis, commit history parsing, metric calculation, and
result generation occur exclusively on the User's local machine (the
"Current Device"). The Plugin does not establish any outbound network
connections to transmit analysis results, intermediate data, or any
derivative information to Gradum's developers, third-party analytics
services, cloud infrastructures, or any external entities. The
analysis results are rendered solely within the User's local IDE
interface and are accessible only to the User. No data is ever stored
on any remote server, content delivery network, or logging service
controlled by Gradum or its affiliates. This architecture ensures that
the User retains complete physical and logical control over their
proprietary source code and development metadata at all times.

**3. Explicit User Activation and Purpose Limitation**

The Plugin operates strictly on an "opt-in" and "action-triggered"
basis. The Plugin does not perform any background scanning, passive
monitoring, or automatic data collection. The reading of a
repository's commit history, the only data operation performed by the
Plugin, is initiated solely and deliberately by the User through a
specific UI command or keyboard shortcut. This action is never
automated, scheduled, or performed without the User's clear and
unambiguous affirmative consent at the moment of execution. The commit
history data is accessed for the singular, limited purpose of
generating productivity and contribution metrics relevant to the
User's immediate analytical needs. The Plugin explicitly disclaims any
use of the repository's source code, commit messages, author
information, or timestamps for:

(a) training machine learning or large language models;

(b) profiling, advertising, or marketing purposes;

(c) any form of behavioral or performance benchmarking against other
users; or

(d) any data aggregation or anonymization for external research.

No portion of the codebase is ever uploaded, copied, or transferred
outside the local environment.

**4. Ephemeral Data Retention and Non-Persistent Storage**

Any audit trail, intermediate computation cache, or session-specific
data generated during the Plugin's operation is stored exclusively in
temporary operating system files designated for the current user
session. These temporary files are subject to the following strict
lifecycle policies:

- **Retention:** Data persists only for the duration of the active
  IDE session.
- **Automatic Deletion:** Upon the normal or forced termination of
  the IDE session, the Plugin executes a secure erasure routine that
  deletes all temporary files and associated data structures.
- **No Residual Data:** The Plugin does not write any logs,
  configuration files, or persistent caches to the User's file system
  beyond the transient session scope.
- **Immutable Project State:** The Plugin strictly prohibits any
  modification, insertion, deletion, or alteration of files within the
  User's project directories, including but not limited to source code
  files, configuration files (e.g., `.git`, `.gradle`,
  `package.json`), documentation, or build artifacts. The Plugin is a
  read-only analytical tool with no write privileges to the repository
  or its working tree.

**5. Full Source Code Transparency and Open-Source Auditability**

In furtherance of Gradum's commitment to trust and verifiability, the
complete and unabridged source code of the Plugin is made publicly
available in a dedicated open-source repository. This repository
contains the full build scripts, dependency manifests, and all
functional modules. This transparency enables independent security
researchers, enterprise audit teams, and individual Users to inspect,
compile, and verify every line of code executed by the Plugin. The
public availability of the source code ensures that the security and
privacy claims made in this Statement are objectively verifiable and
not merely aspirational. The GitHub repository serves as the
authoritative distribution channel for all versions of the Plugin, and
Users are encouraged to review the source code prior to installation.

**6. Authoritative Version and Modification Policy**

This Statement takes effect on the date indicated above and supersedes
all prior verbal or written representations regarding data handling.
Gradum reserves the right to update or amend this Statement to reflect
changes in legal requirements, operational practices, or Plugin
functionality. The most current, legally binding version of this
Statement is maintained and version-controlled within the Gradum
open-source repository. Any modifications to this Statement will be
clearly documented through commit history and semantic versioning. In
the event of any discrepancy, ambiguity, or conflict between the text
of this Statement as displayed in any third-party marketplace, IDE
plugin repository, or offline documentation, and the text of the
authoritative version stored in the official Gradum GitHub repository,
the latter shall prevail and control.

**7. User Acknowledgment and Acceptance**

By continuing to use the Plugin after the effective date, the User
acknowledges and accepts the terms of this Statement. The User
understands that Gradum does not provide warranties regarding the
completeness of analysis but guarantees the privacy and security of
the underlying data as articulated herein.

**Gradum Official Repository:**
[https://github.com/gradum/gradum](https://github.com/gradum/gradum)