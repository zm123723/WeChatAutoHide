package com.github.wechatautohide

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
    
    private val viewModel: ContactViewModel by viewModels()
    private lateinit var adapter: ContactAdapter
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var fabAddContact: FloatingActionButton
    private lateinit var cardAccessibility: MaterialCardView
    private lateinit var tvAccessibilityStatus: android.widget.TextView
    private lateinit var tvEmptyView: android.widget.TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initViews()
        setupRecyclerView()
        setupObservers()
        setupListeners()
        checkPermissions()
    }
    
    private fun initViews() {
        recyclerView = findViewById(R.id.recycler_view)
        fabAddContact = findViewById(R.id.fab_add_contact)
        cardAccessibility = findViewById(R.id.card_accessibility)
        tvAccessibilityStatus = findViewById(R.id.tv_accessibility_status)
        tvEmptyView = findViewById(R.id.tv_empty_view)
    }
    
    private fun setupRecyclerView() {
        adapter = ContactAdapter { contact ->
            showDeleteConfirmDialog(contact)
        }
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }
    
    private fun setupObservers() {
        viewModel.allContacts.observe(this) { contacts ->
            adapter.submitList(contacts)
            tvEmptyView.visibility = if (contacts.isEmpty()) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        }
    }
    
    private fun setupListeners() {
        fabAddContact.setOnClickListener {
            showAddContactDialog()
        }
        
        cardAccessibility.setOnClickListener {
            PermissionHelper.openAccessibilitySettings(this)
        }
    }
    
    private fun checkPermissions() {
        val accessibilityEnabled = PermissionHelper.isAccessibilityServiceEnabled(
            this,
            "com.github.wechatautohide.service.WeChatMonitorService"
        )
        updateServiceStatus(tvAccessibilityStatus, accessibilityEnabled)
    }
    
    private fun updateServiceStatus(textView: android.widget.TextView, enabled: Boolean) {
        if (enabled) {
            textView.text = "✅ 已启用"
            textView.setTextColor(getColor(android.R.color.holo_green_dark))
        } else {
            textView.text = "❌ 未启用"
            textView.setTextColor(getColor(android.R.color.holo_red_dark))
        }
    }
    
    private fun showAddContactDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_contact, null)
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
                
                val contact = HideContact(
                    name = name,
                    groupType = groupType,
                    autoHide = autoHideCheckbox.isChecked,
                    muteNotification = muteCheckbox.isChecked,
                    autoRead = autoReadCheckbox.isChecked
                )
                
                viewModel.addContact(contact)
                Toast.makeText(this, "已添加: $name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showDeleteConfirmDialog(contact: HideContact) {
        AlertDialog.Builder(this)
            .setTitle("删除确认")
            .setMessage("确定要删除 ${contact.name} 吗？")
            .setPositiveButton("删除") { _, _ ->
                viewModel.deleteContact(contact)
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    override fun onResume() {
        super.onResume()
        checkPermissions()
    }
}
