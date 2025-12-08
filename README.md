# oct-intellij
Integration of Open Collaboration Tools with the IntelliJ Platform

# Extension Build 
To build the extension, you need to have 
[open-collaboration-tools](https://github.com/eclipse-oct/open-collaboration-tools) available on your system.
The path to the project can be configured in the `gradle.properties` file using the `org.typefox.oct-project-path` property. 
`npm install` has to have been run in the `open-collaboration-tools` project before building the extension.
This allows the service process to be built and packaged together with the IntelliJ extension.


# known issues

- if the eol does not align (like having crlf in vscode while intellJ always has lf) 
  it can happen that changes are duplicated