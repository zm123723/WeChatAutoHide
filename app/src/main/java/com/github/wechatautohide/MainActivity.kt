package com.github.wechatautohide

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.wechatautohide.adapter.ContactAdapter
import com.github.wechatautohide.database.HideContact
import com.github.wechatautohide.util.PermissionHelper
import com.github.wechatautohide.viewmodel.ContactViewModel
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    private var viewModel: ContactViewModel? = null
    private var adapter: ContactAdapter? = null
    private var recyclerView: RecyclerView? = null
    private var fabAddContact: FloatingActionButton? = null
    private var cardAccessibility: MaterialCardView? = null
    private var tvAccessibilityStatus: TextView? = null
    private var tvEmptyView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recycler_view)
        fabAddContact = findViewById(R.id.fab_add_contact)
        cardAccessibility = findViewById(R.id.card_accessibility)
        tvAccessibilityStatus = findViewById(R.id.tv_accessibility_status)
        tvEmptyView = findViewById(R.id.tv_empty_view)

        viewModel = ViewModelProvider(this)[ContactViewModel::class.java]

        adapter = ContactAdapter { contact ->
            AlertDialog.Builder(this)
                .setTitle("删除确认")
                .setMessage("确定要删除 ${contact.name} 吗？")
                .setPositiveButton("删除") { _, _ ->
                    viewModel?.deleteContact(contact)
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        recyclerView?.layoutManager = LinearLayoutManager(this)
        recyclerView?.adapter = adapter

        viewModel?.allContacts?.observe(this) { contacts ->
            adapter?.submitList(contacts)
            tvEmptyView?.visibility = if (contacts.isEmpty()) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        }

        fabAddContact?.setOnClickListener {
            showAddContactDialog()
        }

        cardAccessibility?.setOnClickListener {
            PermissionHelper.openAccessibilitySettings(this)
        }

        updateStatus()
    }

    private fun updateStatus() {
        val enabled = try {
            PermissionHelper.isAccessibilityServiceEnabled(
                this,
                "com.github.wechatautohide.service.WeChatMonitorService"
            )
        } catch (e: Exception) {
            false
        }

        tvAccessibilityStatus?.text = if (enabled) "✅ 已启用" else "❌ 未启用"
        tvAccessibilityStatus?.setTextColor(
            if (enabled) {
                getColor(android.R.color.holo_green_dark)
            } else {
                getColor(android.R.color.holo_red_dark)
            }
        )
    }

    private fun showAddContactDialog() {
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_add_contact, null)
        val nameInput = dialogView.findViewById<EditText>(R.id.et_contact_name)
        val groupRadio = dialogView.findViewById<RadioGroup>(R.id.rg_group_type)
        val autoHideCheckbox = dialogView.findViewById<MaterialCheckBox>(R.id.cb_auto_hide)
        val muteCheckbox = dialogView.findViewById<MaterialCheckBox>(R.id.cb_mute_notification)
        val autoReadCheckbox = dialogView.findViewById<MaterialCheckBox>(R.id.cb_auto_read)

        AlertDialog.Builder(this)
            .setTitle("添加联系人")
            .setView(dialogView)
            .setPositiveButton("添加") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "请输入联系人名称", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val groupType = when (groupRadio.checkedRadioButtonId) {
                    R.id.rb_work -> "工作"
                    R.id.rb_life -> "生活"
                    else -> "其他"
                }
                viewModel?.addContact(
                    HideContact(
                        name = name,
                        groupType = groupType,
                        autoHide = autoHideCheckbox.isChecked,
                        muteNotification = muteCheckbox.isChecked,
                        autoRead = autoReadCheckbox.isChecked
                    )
                )
                Toast.makeText(this, "已添加: $name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }
}
