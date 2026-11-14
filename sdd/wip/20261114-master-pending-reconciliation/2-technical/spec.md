# Spec técnico

El cambio es exclusivamente Markdown/JSON SDD. No altera clases, estados,
adaptadores, endpoints, esquema, CI ni configuración. La evidencia se vincula
a commits, PRs y runs existentes; los estados históricos se reconcilian sin
reescribir sus bases ni simular `sdd.finish`.

La salida debe superar validación JSON y `git diff --check`, recibir revisión
independiente y recorrer PR, CI y post-merge.
