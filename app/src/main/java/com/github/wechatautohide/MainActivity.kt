package com.github.wechatautohide

import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
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
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    private var viewModel: ContactViewModel? = null
    private var adapter: ContactAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val recyclerView = findViewById<RecyclerView>(R.id.recycler_view)
        val fabAddContact = findViewById<FloatingActionButton>(R.id.fab_add_contact)
        val cardAccessibility = findViewById<LinearLayout>(R.id.card_accessibility)
        val tvAccessibilityStatus = findViewById<TextView>(R.id.tv_accessibility_status)
        val tvEmptyView = findViewById<TextView>(R.id.tv_empty_view)

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

        // 点击加号：完全不用布局文件，直接用代码创建输入框
        fabAddContact?.setOnClickListener {
            val editText = EditText(this)
            editText.hint = "输入联系人名称（与微信完全一致）"
            editText.setPadding(48, 24, 48, 24)

            AlertDialog.Builder(this)
                .setTitle("添加隐藏联系人")
                .setView(editText)
                .setPositiveButton("添加") { _, _ ->
                    val name = editText.text.toString().trim()
                    if (name.isEmpty()) {
                        Toast.makeText(this, "请输入联系人名称", Toast.LENGTH_SHORT).show()
                    } else {
                        viewModel?.addContact(
                            HideContact(name = name)
                        )
                        Toast.makeText(this, "✅ 已添加: $name", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        cardAccessibility?.setOnClickListener {
            PermissionHelper.openAccessibilitySettings(this)
        }

        // 更新无障碍服务状态
        val enabled = try {
            PermissionHelper.isAccessibilityServiceEnabled(
                this,
                "com.github.wechatautohide.service.WeChatMonitorService"
            )
        } catch (e: Exception) {
            false
        }
        tvAccessibilityStatus?.text = if (enabled) "✅ 已启用" else "❌ 未启用（点击设置）"
        tvAccessibilityStatus?.setTextColor(
            if (enabled) getColor(android.R.color.holo_green_dark)
            else getColor(android.R.color.holo_red_dark)
        )
    }

    override fun onResume() {
        super.onResume()
        val tvAccessibilityStatus = findViewById<TextView>(R.id.tv_accessibility_status)
        val enabled = try {
            PermissionHelper.isAccessibilityServiceEnabled(
                this,
                "com.github.wechatautohide.service.WeChatMonitorService"
            )
        } catch (e: Exception) {
            false
        }
        tvAccessibilityStatus?.text = if (enabled) "✅ 已启用" else "❌ 未启用（点击设置）"
        tvAccessibilityStatus?.setTextColor(
            if (enabled) getColor(android.R.color.holo_green_dark)
            else getColor(android.R.color.holo_red_dark)
        )
    }
}
