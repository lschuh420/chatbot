## Vorraussetzungen:
- MVN Version 3.9.9 und JDK 17
- Verbindung mit EduVPN Standardnetz Studierende muss bestehen (für das LLM)
- Docker Container starten (in der Projekt-Kommandozeile) mit:
    - docker run -p 6333:6333 -p 6334:6334 qdrant/qdrant
- Lombok Plugin in IntelliJ benötigt → Lombok Annotation Processor muss verwendet werden

Funktioniert folgendermaßen:

In IntelliJ navigieren zu File → Settings (oder IntelliJ IDEA → Preferences auf Mac)    
Navigieren zu Build, Execution, Deployment → Compiler   
→ Annotation Processors
"Enable annotation processing" muss an sein     
→ Alternativ beim 1. Ausführen der StudyChatApplication das IntelliJ Dialogfenster bzgl. des Plug-Ins bestätigen