package com.example.sshproxy.ui.instructions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.sshproxy.data.KeyRepository
import com.example.sshproxy.data.PreferencesManager
import com.example.sshproxy.data.ServerRepository
import com.example.sshproxy.databinding.FragmentInstructionsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class InstructionsFragment : Fragment() {
    private var _binding: FragmentInstructionsBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var keyRepository: KeyRepository
    private lateinit var serverRepository: ServerRepository
    private lateinit var preferencesManager: PreferencesManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentInstructionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        keyRepository = KeyRepository(requireContext())
        serverRepository = ServerRepository(requireContext())
        preferencesManager = PreferencesManager(requireContext())
        
        updateInstructions()
        
        binding.btnSelectServer.setOnClickListener {
            showServerSelector()
        }
        
        binding.btnSelectKey.setOnClickListener {
            showKeySelector()
        }
        
        binding.btnCopyInstructions.setOnClickListener {
            copyInstructions()
        }
        
        binding.btnCopyQuick.setOnClickListener {
            copyQuickCommand()
        }
    }

    private fun updateInstructions() {
        val activeKeyId = preferencesManager.getActiveKeyId()
        val activeServerId = preferencesManager.getActiveServerId()
        
        val key = keyRepository.getKeys().find { it.id == activeKeyId }
        val server = serverRepository.getServers().find { it.id == activeServerId }
        
        binding.tvSelectedKey.text = key?.name ?: "No key selected"
        binding.tvSelectedServer.text = server?.name ?: "No server selected"
        
        if (key != null) {
            val username = server?.user ?: "user"
            val instructions = generateInstructions(key.publicKey, username)
            binding.tvInstructions.text = instructions
            
            // Quick command for experienced users
            val quickCommand = generateQuickCommand(key.publicKey, username)
            binding.tvQuickCommand.text = quickCommand
        } else {
            binding.tvInstructions.text = "Please select or generate an SSH key first"
            binding.tvQuickCommand.text = "No key selected"
        }
    }

    private fun generateInstructions(publicKey: String, username: String): String {
        return """
# SSH Server Setup Instructions

## 1. Create restricted user for SSH tunneling:
sudo adduser --disabled-password --gecos "" --home /home/$username $username
sudo usermod -s /usr/sbin/nologin $username

## 2. Setup SSH key authentication:
sudo install -d -m 700 -o $username -g $username /home/$username/.ssh
sudo bash -c 'echo "restrict,port-forwarding $publicKey" > /home/$username/.ssh/authorized_keys'
sudo chown $username:$username /home/$username/.ssh/authorized_keys
sudo chmod 600 /home/$username/.ssh/authorized_keys

## 3. Configure SSH restrictions for the user:
sudo bash -c 'cat >/etc/ssh/sshd_config.d/$username.conf <<EOF
Match User $username
    PasswordAuthentication no
    AllowTcpForwarding local
    PermitTTY no
    X11Forwarding no
    PermitTunnel no
    GatewayPorts no
EOF'

## 4. Test and reload SSH configuration:
sudo sshd -t
sudo systemctl reload sshd || sudo systemctl reload ssh

## 5. Install and configure HTTP proxy (Privoxy or Tinyproxy):

### Option A: Privoxy (recommended, better privacy features)
# Detect package manager and install
if command -v apt-get >/dev/null 2>&1; then
    sudo apt-get update && sudo apt-get install -y privoxy
elif command -v yum >/dev/null 2>&1; then
    sudo yum install -y privoxy
elif command -v dnf >/dev/null 2>&1; then
    sudo dnf install -y privoxy
elif command -v pacman >/dev/null 2>&1; then
    sudo pacman -S --noconfirm privoxy
fi

# Configure Privoxy (listens on 127.0.0.1:8118 by default)
sudo sed -i 's/^listen-address.*/listen-address  127.0.0.1:8118/' /etc/privoxy/config
sudo systemctl restart privoxy
sudo systemctl enable privoxy

### Option B: Tinyproxy (lightweight alternative)
# Detect package manager and install
if command -v apt-get >/dev/null 2>&1; then
    sudo apt-get update && sudo apt-get install -y tinyproxy
elif command -v yum >/dev/null 2>&1; then
    sudo yum install -y tinyproxy
elif command -v dnf >/dev/null 2>&1; then
    sudo dnf install -y tinyproxy
elif command -v pacman >/dev/null 2>&1; then
    sudo pacman -S --noconfirm tinyproxy
fi

# Configure Tinyproxy to listen on port 8118
sudo sed -i 's/^Port.*/Port 8118/' /etc/tinyproxy/tinyproxy.conf
sudo sed -i 's/^Listen.*/Listen 127.0.0.1/' /etc/tinyproxy/tinyproxy.conf
# Allow localhost connections
sudo sed -i 's/^#Allow 127.0.0.1/Allow 127.0.0.1/' /etc/tinyproxy/tinyproxy.conf
sudo systemctl restart tinyproxy
sudo systemctl enable tinyproxy

## Notes:
- The user '$username' is restricted to port forwarding only
- No shell access, no TTY, no X11 forwarding
- Proxy will run on port 8118 (localhost only)
- Test connection: ssh -N -L 8080:127.0.0.1:8118 $username@your-server
- You can use either Privoxy or Tinyproxy, both work well
        """.trimIndent()
    }

    private fun generateQuickCommand(publicKey: String, username: String): String {
        return """#!/bin/bash
# Quick setup script - review before running!
USER="$username"
KEY="restrict,port-forwarding $publicKey"

# Detect package manager
if command -v apt-get >/dev/null 2>&1; then
    PKG_MGR="apt-get"
    PKG_UPDATE="apt-get update"
    PKG_INSTALL="apt-get install -y"
elif command -v yum >/dev/null 2>&1; then
    PKG_MGR="yum"
    PKG_UPDATE="yum check-update || true"
    PKG_INSTALL="yum install -y"
elif command -v dnf >/dev/null 2>&1; then
    PKG_MGR="dnf"
    PKG_UPDATE="dnf check-update || true"
    PKG_INSTALL="dnf install -y"
elif command -v pacman >/dev/null 2>&1; then
    PKG_MGR="pacman"
    PKG_UPDATE="pacman -Sy"
    PKG_INSTALL="pacman -S --noconfirm"
else
    echo "Error: No supported package manager found!"
    exit 1
fi

echo "Using package manager: ${'$'}PKG_MGR"

# Create user and setup SSH
sudo adduser --disabled-password --gecos "" --home /home/${'$'}USER ${'$'}USER && \
sudo usermod -s /usr/sbin/nologin ${'$'}USER && \
sudo install -d -m 700 -o ${'$'}USER -g ${'$'}USER /home/${'$'}USER/.ssh && \
sudo bash -c "echo '${'$'}KEY' > /home/${'$'}USER/.ssh/authorized_keys" && \
sudo chown ${'$'}USER:${'$'}USER /home/${'$'}USER/.ssh/authorized_keys && \
sudo chmod 600 /home/${'$'}USER/.ssh/authorized_keys && \
sudo bash -c "cat >/etc/ssh/sshd_config.d/${'$'}USER.conf <<EOF
Match User ${'$'}USER
    PasswordAuthentication no
    AllowTcpForwarding local
    PermitTTY no
    X11Forwarding no
    PermitTunnel no
    GatewayPorts no
EOF" && \
sudo sshd -t && \
sudo systemctl reload sshd || sudo systemctl reload ssh && \
echo "SSH setup complete"

# Install and configure proxy (try Privoxy first, then Tinyproxy)
echo "Installing HTTP proxy..."
sudo ${'$'}PKG_UPDATE

# Try to install Privoxy
if sudo ${'$'}PKG_INSTALL privoxy 2>/dev/null; then
    echo "Configuring Privoxy..."
    sudo sed -i 's/^listen-address.*/listen-address  127.0.0.1:8118/' /etc/privoxy/config
    sudo systemctl restart privoxy
    sudo systemctl enable privoxy
    echo "Privoxy installed and configured on port 8118"
# If Privoxy fails, try Tinyproxy
elif sudo ${'$'}PKG_INSTALL tinyproxy 2>/dev/null; then
    echo "Configuring Tinyproxy..."
    sudo sed -i 's/^Port.*/Port 8118/' /etc/tinyproxy/tinyproxy.conf
    sudo sed -i 's/^Listen.*/Listen 127.0.0.1/' /etc/tinyproxy/tinyproxy.conf
    sudo sed -i 's/^#Allow 127.0.0.1/Allow 127.0.0.1/' /etc/tinyproxy/tinyproxy.conf
    sudo systemctl restart tinyproxy
    sudo systemctl enable tinyproxy
    echo "Tinyproxy installed and configured on port 8118"
else
    echo "Warning: Could not install HTTP proxy. Please install Privoxy or Tinyproxy manually."
fi

echo ""
echo "Setup complete for user: ${'$'}USER"
echo "Test with: ssh -N -L 8080:127.0.0.1:8118 ${'$'}USER@your-server"
        """.trimIndent()
    }

    private fun showServerSelector() {
        val servers = serverRepository.getServers()
        if (servers.isEmpty()) {
            Toast.makeText(context, "No servers configured", Toast.LENGTH_SHORT).show()
            return
        }

        val serverNames = servers.map { server -> "${server.name} (${server.user}@${server.host})" }.toTypedArray()
        val currentId = preferencesManager.getActiveServerId()
        val currentIndex = servers.indexOfFirst { server -> server.id == currentId }.takeIf { index -> index >= 0 } ?: 0

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Server for Instructions")
            .setSingleChoiceItems(serverNames, currentIndex) { dialog, which ->
                preferencesManager.setActiveServerId(servers[which].id)
                updateInstructions()
                dialog.dismiss()
            }
            .show()
    }

    private fun showKeySelector() {
        val keys = keyRepository.getKeys()
        if (keys.isEmpty()) {
            Toast.makeText(context, "No SSH keys configured", Toast.LENGTH_SHORT).show()
            return
        }

        val keyNames = keys.map { key -> key.name }.toTypedArray()
        val currentId = preferencesManager.getActiveKeyId()
        val currentIndex = keys.indexOfFirst { key -> key.id == currentId }.takeIf { index -> index >= 0 } ?: 0

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select SSH Key")
            .setSingleChoiceItems(keyNames, currentIndex) { dialog, which ->
                preferencesManager.setActiveKeyId(keys[which].id)
                updateInstructions()
                dialog.dismiss()
            }
            .show()
    }

    private fun copyInstructions() {
        val text = binding.tvInstructions.text.toString()
        if (text.isNotBlank() && !text.contains("Please select")) {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Server Instructions", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Instructions copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyQuickCommand() {
        val text = binding.tvQuickCommand.text.toString()
        if (text.isNotBlank() && !text.contains("No key selected")) {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Quick Setup Script", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Quick command copied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
