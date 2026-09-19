# Third-Party Notices for Gradum

**Effective date:** 2026-08-19

**Version:** 0.9.2

This document lists the third-party open-source software distributed with, or linked against by, Gradum (the
"Software"), together with the applicable license terms. It is provided for compliance purposes and does not modify,
supersede, or derogate from any of the licenses referenced herein.

Nothing in this document grants you rights beyond those already granted by the respective licenses of the third-party
components listed below. Where a license requires it, the full license text is made available in the corresponding
upstream project repository. To the extent permitted by law, Gradum is distributed on an "as is" basis, without warranty
of any kind, and each third-party component remains the sole responsibility of its respective copyright holder.

## 1. Scope and Method

The information below was compiled from the Software's dependency declarations (`build.gradle.kts` and
`plugin/build.gradle.kts`) and the resolved dependency graph at the stated effective date. Where a version is qualified
as "bundled with the IDE," the artifact is not fetched from a package repository but is loaded from the host JetBrains
IDE installation (IntelliJ IDEA 2026.2, build `IU-262.8665.258`) and copied into `plugin/libs/`.

## 2. License Summary

| Component                        | Version              | License            | Copyright Holder                   |
|----------------------------------|----------------------|--------------------|------------------------------------|
| Kotlin                           | 2.3.0                | Apache License 2.0 | JetBrains s.r.o.                   |
| Ktor                             | 3.0.3                | Apache License 2.0 | JetBrains s.r.o.                   |
| kotlinx-coroutines-core          | 1.9.0 / 1.11.0       | Apache License 2.0 | JetBrains s.r.o.                   |
| kotlinx-serialization-json       | 1.7.3                | Apache License 2.0 | JetBrains s.r.o.                   |
| Netty                            | 4.2.15.Final         | Apache License 2.0 | The Netty Project                  |
| Logback                          | 1.5.25               | EPL-1.0 / LGPL-2.1 | QOS.ch Sàrl                        |
| Jackson                          | 2.21.1               | Apache License 2.0 | FasterXML, LLC                     |
| MockK                            | 1.13.13              | Apache License 2.0 | Oleksiy Shmalko                    |
| JUnit                            | 4.13.2               | EPL-1.0            | Kent Beck, Erich Gamma, David Saff |
| kotlin-test / kotlin-test-junit5 | 2.1.0                | Apache License 2.0 | JetBrains s.r.o.                   |
| Detekt                           | 1.23.7               | Apache License 2.0 | Artur Bosch                        |
| IntelliJ Platform                | 2026.2               | Apache License 2.0 | JetBrains s.r.o.                   |
| Compose Multiplatform            | 1.7.3                | Apache License 2.0 | JetBrains s.r.o.                   |
| Jewel                            | Bundled with the IDE | Apache License 2.0 | JetBrains s.r.o.                   |
| Skiko                            | Bundled with the IDE | Apache License 2.0 | JetBrains s.r.o.                   |
| LaTeX rendering                  | 1.4.7                | MIT License        | huarangmeng                        |
| JetBrains Changelog Plugin       | 2.3.0                | Apache License 2.0 | JetBrains s.r.o.                   |
| Ktor Gradle Plugin               | 3.0.3                | Apache License 2.0 | JetBrains s.r.o.                   |

## 3. License Texts

### 3.1 Apache License 2.0

Components licensed under the Apache License, Version 2.0 (the "Apache License"): Kotlin, Ktor, kotlinx-coroutines-core,
kotlinx-serialization-json, Netty, Jackson, MockK, kotlin-test, Detekt, IntelliJ Platform, Compose Multiplatform, Jewel,
Skiko, JetBrains Changelog Plugin, and the Ktor Gradle Plugin.

You may obtain a copy of the Apache License at
[Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0). Unless required by applicable law or agreed to in writing,
software distributed under the Apache License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
ANY KIND, either express or implied.

### 3.2 MIT License

Component: LaTeX rendering (`io.github.huarangmeng:latex-*`, version 1.4.7).

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit
persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

### 3.3 Eclipse Public License 1.0

Components: JUnit (version 4.13.2); Logback (`logback-classic`, version 1.5.25)
as an alternative to the GNU Lesser General Public License 2.1.

The full text of the Eclipse Public License 1.0 is available at
[EPL 1.0](https://www.eclipse.org/legal/epl-v10.html).

### 3.4 GNU Lesser General Public License 2.1

Component: Logback (`logback-classic`, version 1.5.25), offered as an alternative to the Eclipse Public License 1.0; you
may choose either license.

The full text of the GNU Lesser General Public License 2.1 is available at
[LGPL-2.1](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html).

## 4. Notes on Bundled Components

The Jewel UI library, Compose runtime/foundation modules, and the Skiko rendering layer are not distributed from a
package repository. They are copied verbatim from the JetBrains IDE distribution (IntelliJ IDEA 2026.2, build
`IU-262.8665.258`) into `plugin/libs/` and loaded at runtime. Their licenses (Apache License 2.0) are the same as those
published by JetBrains s.r.o. for the corresponding upstream projects.

## 5. Contact

Questions regarding these notices should be directed to the Gradum authors via the project repository.
