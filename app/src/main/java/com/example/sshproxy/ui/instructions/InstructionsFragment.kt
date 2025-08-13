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
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.sshproxy.data.KeyRepository
import com.example.sshproxy.data.PreferencesManager
import com.example.sshproxy.data.ServerRepository
import com.example.sshproxy.databinding.FragmentInstructionsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.Socket
import java.net.URL

class InstructionsFragment : Fragment() {
    private var _binding: FragmentInstructionsBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: InstructionsViewModel by viewModels {
        InstructionsViewModelFactory(
            KeyRepository(requireContext()),
            ServerRepository(requireContext()),
            PreferencesManager(requireContext())
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentInstructionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        observeViewModel()
        
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
        
        binding.btnTestNetwork.setOnClickListener {
            runNetworkTest()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            combine(viewModel.selectedKey, viewModel.selectedServer) { key, server ->
                Pair(key, server)
            }.collect { (key, server) ->
                binding.tvSelectedKey.text = key?.name ?: "No key selected"
                binding.tvSelectedServer.text = server?.name ?: "No server selected"

                if (key != null) {
                    val username = server?.username ?: "sshproxy"
                    binding.tvInstructions.text = generateInstructions(key.publicKey, username)
                    binding.tvQuickCommand.text = generateQuickCommand(key.publicKey, username)
                } else {
                    binding.tvInstructions.text = "Please select or generate an SSH key first"
                    binding.tvQuickCommand.text = "No key selected"
                }
            }
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
# Auto-detect SSH service name
if systemctl is-active --quiet ssh; then
    sudo systemctl reload ssh
elif systemctl is-active --quiet sshd; then
    sudo systemctl reload sshd
else
    # Try both service names
    sudo systemctl reload ssh || sudo systemctl reload sshd
fi

## 5. Install and configure HTTP proxy (Tinyproxy):
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
- Tinyproxy will run on port 8118 (localhost only)
- Test connection: ssh -N -L 8080:127.0.0.1:8118 $username@your-server
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

# Auto-detect SSH service name
if systemctl is-active --quiet ssh; then
    SSH_SERVICE="ssh"
elif systemctl is-active --quiet sshd; then
    SSH_SERVICE="sshd"
else
    SSH_SERVICE="ssh"  # Default to ssh for Ubuntu/Debian
fi

echo "SSH service detected: ${'$'}SSH_SERVICE"

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
sudo systemctl reload ${'$'}SSH_SERVICE && \
echo "SSH setup complete"

# Install and configure Tinyproxy
echo "Installing Tinyproxy..."
sudo ${'$'}PKG_UPDATE

if sudo ${'$'}PKG_INSTALL tinyproxy; then
    echo "Configuring Tinyproxy..."
    sudo sed -i 's/^Port.*/Port 8118/' /etc/tinyproxy/tinyproxy.conf
    sudo sed -i 's/^Listen.*/Listen 127.0.0.1/' /etc/tinyproxy/tinyproxy.conf
    sudo sed -i 's/^#Allow 127.0.0.1/Allow 127.0.0.1/' /etc/tinyproxy/tinyproxy.conf
    sudo systemctl restart tinyproxy
    sudo systemctl enable tinyproxy
    echo "Tinyproxy installed and configured on port 8118"
else
    echo "Error: Could not install Tinyproxy. Please install manually."
    exit 1
fi

echo ""
echo "Setup complete for user: ${'$'}USER"
echo "Test with: ssh -N -L 8080:127.0.0.1:8118 ${'$'}USER@your-server"
        """.trimIndent()
    }

    private fun showServerSelector() {
        val servers = viewModel.servers.value
        if (servers.isEmpty()) {
            Toast.makeText(context, "No servers configured", Toast.LENGTH_SHORT).show()
            return
        }

        val serverNames = servers.map { server -> "${server.name} (${server.username}@${server.host})" }.toTypedArray()
        val currentId = viewModel.selectedServer.value?.id
        val currentIndex = servers.indexOfFirst { server -> server.id == currentId }.takeIf { index -> index >= 0 } ?: 0

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Server for Instructions")
            .setSingleChoiceItems(serverNames, currentIndex) { dialog, which ->
                viewModel.selectServer(servers[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun showKeySelector() {
        val keys = viewModel.keys.value
        if (keys.isEmpty()) {
            Toast.makeText(context, "No SSH keys configured", Toast.LENGTH_SHORT).show()
            return
        }

        val keyNames = keys.map { key -> key.name }.toTypedArray()
        val currentId = viewModel.selectedKey.value?.id
        val currentIndex = keys.indexOfFirst { key -> key.id == currentId }.takeIf { index -> index >= 0 } ?: 0

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select SSH Key")
            .setSingleChoiceItems(keyNames, currentIndex) { dialog, which ->
                viewModel.selectKey(keys[which])
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
    
    private fun runNetworkTest() {
        val server = viewModel.selectedServer.value
        if (server == null) {
            Toast.makeText(context, "Please select a server first", Toast.LENGTH_SHORT).show()
            return
        }
        
        Toast.makeText(context, "Running network test...", Toast.LENGTH_SHORT).show()
        
        viewLifecycleOwner.lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                val testResults = mutableListOf<String>()
                
                // Тест 1: DNS разрешение
                try {
                    val address = InetAddress.getByName(server.host)
                    testResults.add("✓ DNS Resolution: ${server.host} → ${address.hostAddress}")
                } catch (e: Exception) {
                    testResults.add("✗ DNS Resolution: Failed - ${e.message}")
                }
                
                // Тест 2: HTTP подключение (проверяем общую сетевую доступность)
                try {
                    val url = URL("https://www.google.com")
                    val connection = url.openConnection()
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.connect()
                    testResults.add("✓ Internet Access: Working")
                } catch (e: Exception) {
                    testResults.add("✗ Internet Access: Failed - ${e.message}")
                }
                
                // Тест 3: Loopback (базовая функциональность сокетов)
                try {
                    val localSocket = Socket()
                    localSocket.connect(java.net.InetSocketAddress("127.0.0.1", 1), 1000)
                    localSocket.close()
                    testResults.add("✓ Local Sockets: Working")
                } catch (e: Exception) {
                    testResults.add("✗ Local Sockets: ${e.message}")
                }
                
                // Тест 4: SSH подключение к серверу
                try {
                    val sshSocket = Socket()
                    sshSocket.connect(java.net.InetSocketAddress(server.host, server.port), 10000)
                    sshSocket.close()
                    testResults.add("✓ SSH Server (${server.host}:${server.port}): Reachable")
                } catch (e: Exception) {
                    testResults.add("✗ SSH Server (${server.host}:${server.port}): ${e.message}")
                }
                
                // Тест 5: Информация о процессе
                testResults.add("")
                testResults.add("=== Debug Information ===")
                testResults.add("Process UID: ${android.os.Process.myUid()}")
                testResults.add("Process PID: ${android.os.Process.myPid()}")
                testResults.add("Thread: ${Thread.currentThread().name}")
                
                testResults
            }
            
            // Показываем результаты в диалоге
            val message = results.joinToString("\n")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Network Test Results")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .setNegativeButton("Copy Results") { _, _ ->
                    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Network Test Results", message)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Results copied to clipboard", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}