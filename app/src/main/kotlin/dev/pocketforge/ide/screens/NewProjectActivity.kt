package dev.pocketforge.ide.screens

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import dev.pocketforge.engine.projects.License
import dev.pocketforge.engine.projects.PackageNames
import dev.pocketforge.engine.projects.ProjectNames
import dev.pocketforge.engine.projects.TemplateInput
import dev.pocketforge.engine.projects.Templates
import dev.pocketforge.ide.ui.BaseActivity
import dev.pocketforge.ide.ui.Btn
import dev.pocketforge.ide.ui.simpleWatcher
import dev.pocketforge.ide.ui.value

/** Project wizard: template, name, package id, min SDK, license and README — validated as you type. */
class NewProjectActivity : BaseActivity() {
    private var template = 0
    private var license = License.NONE
    private var readme = true
    private var packageEdited = false
    private lateinit var name: EditText
    private lateinit var pkg: EditText
    private lateinit var minSdk: EditText
    private lateinit var author: EditText
    private lateinit var nameErr: TextView
    private lateinit var pkgErr: TextView
    private lateinit var info: TextView
    private lateinit var pkgBlock: View
    private lateinit var create: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (root, col) = ui.page(ui.topBar("New project", "Created in app-private storage", { finish() }))
        val templates = Templates.all
        info = ui.caption("")
        col.addView(ui.labeled("Template", ui.spinner(templates.map { it.title }, 0) { template = it; refresh() }))
        col.addView(info.apply { setPadding(ui.dp(2), ui.dp(8), 0, 0) })

        name = ui.input("My App")
        nameErr = ui.caption("", p.danger)
        col.addView(ui.labeled("Project name", name))
        col.addView(nameErr)

        pkg = ui.input("com.example.myapp", monoFont = true)
        pkgErr = ui.caption("", p.danger)
        minSdk = ui.input("26", "26").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        pkgBlock = ui.vertical {
            addView(ui.labeled("Package name (application ID)", pkg, "Must be unique on the device and in app stores. Cannot easily be changed later."))
            addView(pkgErr)
            addView(ui.labeled("Minimum Android version (API level)", minSdk, "26 = Android 8.0. Lower values reach more devices but limit available APIs."))
        }
        col.addView(pkgBlock)

        col.addView(ui.labeled("License", ui.spinner(License.values().map { it.label }, 0) { license = License.values()[it] }))
        author = ui.input("Your name (for the license)")
        col.addView(ui.labeled("Author", author))
        col.addView(ui.checkbox("Add README.md with build prerequisites", true) { readme = it })
        col.addView(ui.space(16))
        create = ui.button("Create project", Btn.PRIMARY) { createProject() }
        col.addView(create)
        col.addView(ui.space(24))

        name.addTextChangedListener(simpleWatcher {
            if (!packageEdited) pkg.setText(PackageNames.suggestFrom(it.ifBlank { "app" }))
            validate()
        })
        pkg.addTextChangedListener(simpleWatcher { validate() })
        pkg.setOnFocusChangeListener { _, has -> if (has) packageEdited = true }
        setScreen(root)
        refresh()
    }

    private fun refresh() {
        val t = Templates.all[template]
        info.text = "${t.description}\n\nPrerequisites:\n" + t.prerequisites.joinToString("\n") { "• $it" }
        pkgBlock.visibility = if (t.needsPackageId) View.VISIBLE else View.GONE
        validate()
    }

    private fun validate(): Boolean {
        val t = Templates.all[template]
        val ne = ProjectNames.validate(name.value())
        val pe = if (t.needsPackageId) PackageNames.validate(pkg.value().trim()) else null
        nameErr.text = if (name.value().isEmpty()) "" else ne ?: ""
        pkgErr.text = pe ?: ""
        val ok = ne == null && pe == null
        ui.setEnabled(create, ok)
        return ok
    }

    private fun createProject() {
        if (!validate()) return
        val t = Templates.all[template]
        val min = minSdk.value().toIntOrNull() ?: 26
        if (t.needsPackageId && min !in 21..36) { pkgErr.text = "Minimum SDK must be between 21 and 36."; return }
        val input = TemplateInput(name.value().trim(), pkg.value().trim(), min, readme, license, author.value().trim())
        ui.setEnabled(create, false)
        bg({ c.projects.create(t.id, input) }) { r ->
            r.onSuccess { proj ->
                c.log.info("project", "created ${proj.templateId} ${proj.id.take(8)}")
                c.settings.update { s -> s.copy(trustedProjects = s.trustedProjects + proj.id) } // created here = trusted
                finish()
                Nav.project(this, proj.id, firstFile(t.id, input))
            }.onFailure { e -> ui.setEnabled(create, true); showError(e, "Could not create the project") }
        }
    }

    private fun firstFile(templateId: String, i: TemplateInput): String? = when (templateId) {
        "android-lite" -> "app/src/main/java/${i.packageId.replace('.', '/')}/MainActivity.java"
        "android-compose" -> "app/src/main/java/${i.packageId.replace('.', '/')}/MainActivity.kt"
        "kotlin-jvm" -> "src/main/kotlin/${i.packageId.replace('.', '/')}/Main.kt"
        "web" -> "index.html"
        "python" -> "main.py"
        else -> if (i.includeReadme) "README.md" else null
    }
}
