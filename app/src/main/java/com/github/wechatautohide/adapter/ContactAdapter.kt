package com.github.wechatautohide.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.wechatautohide.R
import com.github.wechatautohide.database.HideContact

class ContactAdapter(
    private val onDeleteClick: (HideContact) -> Unit
) : ListAdapter<HideContact, ContactAdapter.ContactViewHolder>(ContactDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false)
        return ContactViewHolder(view, onDeleteClick)
    }
    
    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    class ContactViewHolder(
        itemView: View,
        private val onDeleteClick: (HideContact) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        
        private val nameText: TextView = itemView.findViewById(R.id.tv_contact_name)
        private val groupText: TextView = itemView.findViewById(R.id.tv_group_type)
        private val countText: TextView = itemView.findViewById(R.id.tv_hide_count)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.btn_delete)
        
        fun bind(contact: HideContact) {
            nameText.text = contact.name
            groupText.text = contact.groupType
            countText.text = "已隐藏 ${contact.hideCount} 次"
            
            deleteButton.setOnClickListener {
                onDeleteClick(contact)
            }
        }
    }
    
    class ContactDiffCallback : DiffUtil.ItemCallback<HideContact>() {
        override fun areItemsTheSame(oldItem: HideContact, newItem: HideContact): Boolean {
            return oldItem.id == newItem.id
        }
        
        override fun areContentsTheSame(oldItem: HideContact, newItem: HideContact): Boolean {
            return oldItem == newItem
        }
    }
}
