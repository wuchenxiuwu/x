package com.proterm.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.proterm.app.databinding.ItemContainerBinding

/**
 * 容器列表 RecyclerView 适配器 — Material 3 Expressive 卡片。
 */
class ContainerAdapter :
    ListAdapter<ContainerInfo, ContainerAdapter.VH>(ContainerDiffCallback()) {

    var onToggleClick: ((ContainerInfo) -> Unit)? = null
    var onTerminalClick: ((ContainerInfo) -> Unit)? = null
    var onCardClick: ((ContainerInfo) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemContainerBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val binding: ItemContainerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ContainerInfo) {
            binding.tvName.text = item.name
            binding.tvImage.text = item.image
            binding.tvShell.text = item.shell

            // 状态指示
            when (item.status) {
                ContainerStatus.RUNNING -> {
                    binding.statusDot.setBackgroundResource(R.drawable.dot_status)
                    binding.tvStatus.text = itemView.context.getString(R.string.container_status_running)
                    binding.tvStatus.setTextColor(itemView.context.getColor(R.color.md_on_primary_container))
                    binding.tvStatus.setBackgroundResource(R.drawable.bg_status_chip)
                    binding.btnToggle.text = itemView.context.getString(R.string.btn_stop)
                    binding.btnToggle.setIconResource(R.drawable.ic_stop)
                    binding.btnToggle.isEnabled = true
                    binding.btnTerminal.isEnabled = true
                }
                ContainerStatus.STOPPED -> {
                    binding.statusDot.setBackgroundResource(R.drawable.dot_status_stopped)
                    binding.tvStatus.text = itemView.context.getString(R.string.container_status_stopped)
                    binding.tvStatus.setTextColor(itemView.context.getColor(R.color.md_on_error_container))
                    binding.tvStatus.setBackgroundResource(R.drawable.bg_status_chip_stopped)
                    binding.btnToggle.text = itemView.context.getString(R.string.btn_start)
                    binding.btnToggle.setIconResource(R.drawable.ic_play)
                    binding.btnToggle.isEnabled = true
                    binding.btnTerminal.isEnabled = false
                }
                ContainerStatus.CREATING -> {
                    binding.statusDot.setBackgroundResource(R.drawable.dot_status)
                    binding.tvStatus.text = itemView.context.getString(R.string.container_status_creating)
                    binding.tvStatus.setTextColor(itemView.context.getColor(R.color.md_on_primary_container))
                    binding.tvStatus.setBackgroundResource(R.drawable.bg_status_chip)
                    binding.btnToggle.text = itemView.context.getString(R.string.container_status_creating)
                    binding.btnToggle.isEnabled = false
                    binding.btnTerminal.isEnabled = false
                }
            }

            binding.btnToggle.setOnClickListener { onToggleClick?.invoke(item) }
            binding.btnTerminal.setOnClickListener { onTerminalClick?.invoke(item) }
            binding.root.setOnClickListener { onCardClick?.invoke(item) }
        }
    }
}

class ContainerDiffCallback : DiffUtil.ItemCallback<ContainerInfo>() {
    override fun areItemsTheSame(old: ContainerInfo, new: ContainerInfo): Boolean =
        old.id == new.id

    override fun areContentsTheSame(old: ContainerInfo, new: ContainerInfo): Boolean =
        old == new
}
