package com.example.btfileshare

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class DeviceAdapter(
    private val onClick: (DeviceInfo) -> Unit
) : ListAdapter<DeviceInfo, DeviceAdapter.DeviceViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(getItem(position), onClick)
    }

    class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: TextView = itemView.findViewById(R.id.device_icon)
        private val name: TextView = itemView.findViewById(R.id.device_name)
        private val details: TextView = itemView.findViewById(R.id.device_details)

        @SuppressLint("SetTextI18n")
        fun bind(device: DeviceInfo, onClick: (DeviceInfo) -> Unit) {
            icon.text = if (device.isApple) "🍎" else "📱"
            name.text = device.displayName
            val type = when {
                device.isApple -> "Apple / iOS"
                device.paired -> "Android appairé"
                else -> "Android"
            }
            val rssi = if (device.rssi != Int.MIN_VALUE) " · ${device.rssi} dBm" else ""
            details.text = "$type · ${device.address}$rssi"
            itemView.setOnClickListener { onClick(device) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DeviceInfo>() {
            override fun areItemsTheSame(oldItem: DeviceInfo, newItem: DeviceInfo) =
                oldItem.address == newItem.address

            override fun areContentsTheSame(oldItem: DeviceInfo, newItem: DeviceInfo) =
                oldItem == newItem
        }
    }
}
