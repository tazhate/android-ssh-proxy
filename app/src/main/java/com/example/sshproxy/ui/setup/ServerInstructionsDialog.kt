package com.example.sshproxy.ui.setup

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.example.sshproxy.data.PreferencesManager
import com.example.sshproxy.data.SshKeyManager
import com.example.sshproxy.databinding.DialogServerInstructionsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ServerInstructionsDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogServerInstructionsBinding.inflate(layoutInflater)
        val keyManager = SshKeyManager(requireContext())
        val preferencesManager = PreferencesManager(requireContext())
        val activeKeyId = preferencesManager.getActiveKeyId()
        
        val publicKey = activeKeyId?.let { keyManager.getPublicKey(it) }
        
        // Generate instructions with the actual public key
        if (publicKey != null) {
            val instructions = generateInstructions(publicKey)
            binding.tvInstructions.text = instructions
            binding.btnCopyInstructions.setOnClickListener {
                copyToClipboard(instructions)
            }
        } else {
            binding.tvInstructions.text = "Error: No active SSH key found. Please generate or select a key first."
            binding.btnCopyInstructions.isEnabled = false
        }
        
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle("Server Setup Instructions")
            .setView(binding.root)
            .setPositiveButton("Close", null)
            .create()
    }
    
    private fun generateInstructions(publicKey: String): String {
        return """
# SSH Server Setup Instructions

## Quick automated setup (copy and run as root):
bash <(cat <<'SCRIPT'
# Detect package manager
if command -v apt-get >/dev/null 2>&1; then
    PKG_INSTALL="apt-get update && apt-get install -y"
elif command -v yum >/dev/null 2>&1; then
    PKG_INSTALL="yum install -y"
elif command -v dnf >/dev/null 2>&1; then
    PKG_INSTALL="dnf install -y"
elif command -v pacman >/dev/null 2>&1; then
    PKG_INSTALL="pacman -Sy --noconfirm"
else
    echo "No supported package manager found!"; exit 1
fi

# Setup user
adduser --disabled-password --gecos "" --home /home/user user
usermod -s /usr/sbin/nologin user
install -d -m 700 -o user -g user /home/user/.ssh
echo "restrict,port-forwarding $publicKey" > /home/user/.ssh/authorized_keys
chown user:user /home/user/.ssh/authorized_keys
chmod 600 /home/user/.ssh/authorized_keys

# Configure SSH
cat >/etc/ssh/sshd_config.d/user.conf <<EOF
Match User user
    PasswordAuthentication no
    AllowTcpForwarding local
    PermitTTY no
    X11Forwarding no
    PermitTunnel no
    GatewayPorts no
EOF

sshd -t && (systemctl reload sshd || systemctl reload ssh)

# Install proxy (try both)
${'$'}PKG_INSTALL privoxy || ${'$'}PKG_INSTALL tinyproxy

# Configure whichever is installed
if command -v privoxy >/dev/null 2>&1; then
    sed -i 's/^listen-address.*/listen-address  127.0.0.1:8118/' /etc/privoxy/config
    systemctl restart privoxy && systemctl enable privoxy
    echo "Privoxy configured on port 8118"
elif command -v tinyproxy >/dev/null 2>&1; then
    sed -i 's/^Port.*/Port 8118/' /etc/tinyproxy/tinyproxy.conf
    sed -i 's/^Listen.*/Listen 127.0.0.1/' /etc/tinyproxy/tinyproxy.conf
    systemctl restart tinyproxy && systemctl enable tinyproxy
    echo "Tinyproxy configured on port 8118"
fi

echo "Setup complete! Test: ssh -N -L 8080:127.0.0.1:8118 user@your-server"
SCRIPT
)

## Manual steps (if automatic setup fails):
1. Create restricted user
2. Add SSH key with restrictions
3. Configure SSH daemon
4. Install Privoxy or Tinyproxy
5. Configure proxy on port 8118
        """.trimIndent()
    }
    
    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Server Instructions", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Instructions copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}
