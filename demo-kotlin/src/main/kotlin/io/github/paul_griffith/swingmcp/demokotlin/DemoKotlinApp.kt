package io.github.paul_griffith.swingmcp.demokotlin

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Window
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * A small, idiomatic-Kotlin Swing application — the Kotlin twin of `demo-java`'s `DemoApp`.
 *
 * Its whole point is to prove the swing-mcp agent needs nothing special for Kotlin-authored UIs:
 * Kotlin compiles to the very same `javax.swing` classes, so introspection, screenshots, and
 * driving all work unchanged. Every interesting component is given a stable name via the
 * [named] helper so tools can address it deterministically.
 */
object DemoKotlinApp {

    @JvmStatic
    fun main(args: Array<String>) {
        SwingUtilities.invokeLater {
            createFrame().apply {
                defaultCloseOperation = JFrame.EXIT_ON_CLOSE
                isVisible = true
            }
        }
    }

    /**
     * Builds the demo frame (packed and positioned, but not shown and with no close operation set),
     * so in-JVM tests can show, snapshot, and dispose it without terminating the process. Must be
     * called on the Event Dispatch Thread.
     */
    fun createFrame(): JFrame {
        val frame = JFrame("swing-mcp kotlin demo").named("kotlinMainFrame")
        frame.contentPane = JPanel(BorderLayout(12, 12)).named("kotlinContentPanel").apply {
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            add(buildForm(), BorderLayout.NORTH)
            add(buildNotes(), BorderLayout.CENTER)
            add(buildButtonBar(frame), BorderLayout.SOUTH)
        }
        frame.preferredSize = Dimension(520, 400)
        frame.pack()
        frame.setLocationRelativeTo(null)
        return frame
    }

    private fun buildForm(): JPanel = JPanel(GridBagLayout()).named("kotlinFormPanel").apply {
        border = BorderFactory.createTitledBorder("Project")

        val constraints = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4)
            anchor = GridBagConstraints.LINE_START
        }

        val nameField = JTextField(20).named("kotlinNameField")
        val ownerField = JTextField(20).named("kotlinOwnerField")
        val statusCombo = JComboBox(arrayOf("Planned", "Active", "Shipped")).named("kotlinStatusCombo")

        addRow(constraints, 0, "Name:", "kotlinNameLabel", nameField)
        addRow(constraints, 1, "Owner:", "kotlinOwnerLabel", ownerField)
        addRow(constraints, 2, "Status:", "kotlinStatusLabel", statusCombo)
    }

    private fun JPanel.addRow(c: GridBagConstraints, row: Int, labelText: String, labelName: String, field: Component) {
        add(JLabel(labelText).named(labelName), c.apply {
            gridx = 0
            gridy = row
            weightx = 0.0
            fill = GridBagConstraints.NONE
        })
        add(field, c.apply {
            gridx = 1
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
        })
    }

    private fun buildNotes(): JScrollPane {
        val tags = JList(arrayOf("kotlin", "swing", "mcp")).named("kotlinTagList")
        val notes = JTextArea(6, 24).named("kotlinNotesArea").apply {
            lineWrap = true
            wrapStyleWord = true
            text = "Authored in Kotlin. The agent drives it exactly like the Java demo."
        }
        return JScrollPane(JPanel(BorderLayout(8, 8)).named("kotlinNotesPanel").apply {
            border = BorderFactory.createTitledBorder("Notes")
            add(JScrollPane(tags).named("kotlinTagScrollPane"), BorderLayout.WEST)
            add(JScrollPane(notes).named("kotlinNotesScrollPane"), BorderLayout.CENTER)
        }).named("kotlinCenterScrollPane")
    }

    private fun buildButtonBar(owner: Window): JPanel = JPanel().named("kotlinButtonBar").apply {
        layout = BoxLayout(this, BoxLayout.LINE_AXIS)
        add(Box.createHorizontalGlue())
        add(JButton("Reset").named("kotlinResetButton"))
        add(Box.createHorizontalStrut(8))
        add(JButton("Details...").named("kotlinOpenDialogButton").apply {
            addActionListener { showDetailsDialog(owner) }
        })
    }

    private fun showDetailsDialog(owner: Window) {
        val dialog = JDialog(owner, "Kotlin Details", Dialog.ModalityType.APPLICATION_MODAL)
            .named("kotlinDetailsDialog")
        dialog.contentPane = JPanel().named("kotlinDetailsPanel").apply {
            layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
            border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
            add(JLabel("This modal dialog was authored in Kotlin.").named("kotlinDetailsMessageLabel"))
            add(Box.createVerticalStrut(12))
            add(JButton("Close").named("kotlinDetailsCloseButton").apply {
                addActionListener { dialog.dispose() }
            })
        }
        dialog.pack()
        dialog.setLocationRelativeTo(owner)
        dialog.isVisible = true
    }

    /** Sets a component's name and returns it — keeps the builders terse and fluent. */
    private fun <T : Component> T.named(name: String): T = apply { this.name = name }
}
