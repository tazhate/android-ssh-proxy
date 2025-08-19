package com.example.sshproxy.ui.setup

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.example.sshproxy.databinding.DialogServerInstructionsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ServerInstructionsDialog : DialogFragment() {

    companion object {
        private const val ARG_PUBLIC_KEY = "public_key"
        fun newInstance(publicKey: String): ServerInstructionsDialog {
            val args = Bundle()
            args.putString(ARG_PUBLIC_KEY, publicKey)
            val fragment = ServerInstructionsDialog()
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogServerInstructionsBinding.inflate(layoutInflater)
        val publicKey = arguments?.getString(ARG_PUBLIC_KEY)

        if (publicKey != null) {
            val instructions = generateInstructions(publicKey)
            binding.tvInstructions.text = instructions
            binding.btnCopyInstructions.setOnClickListener {
                copyToClipboard(instructions)
            }
        } else {
            binding.tvInstructions.text = getString(com.example.sshproxy.R.string.no_ssh_key_configured) + "\n" + getString(com.example.sshproxy.R.string.please_generate_ssh_key)
            binding.btnCopyInstructions.isEnabled = false
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(com.example.sshproxy.R.string.server_setup_instructions_title))
            .setView(binding.root)
            .setPositiveButton(getString(com.example.sshproxy.R.string.close), null)
            .setNegativeButton(getString(com.example.sshproxy.R.string.skip_for_now)) { _, _ -> dismiss() }
            .create()
        return dialog
    }
    
    private fun generateInstructions(publicKey: String): String {
        return """
# SSH Server Setup Instructions

## Quick automated setup (copy and run as root):
bash <(cat <<'SCRIPT'
# Detect package manager
if command -v apt-get >/dev/null 2>&1; then
    PKG_UPDATE="apt-get update"
    PKG_INSTALL="apt-get install -y"
elif command -v yum >/dev/null 2>&1; then
    PKG_UPDATE="yum check-update || true"
    PKG_INSTALL="yum install -y"
elif command -v dnf >/dev/null 2>&1; then
    PKG_UPDATE="dnf check-update || true"
    PKG_INSTALL="dnf install -y"
elif command -v pacman >/dev/null 2>&1; then
    PKG_UPDATE="pacman -Sy"
    PKG_INSTALL="pacman -S --noconfirm"
else
    echo "No supported package manager found!"; exit 1
fi

# Auto-detect SSH service name
if systemctl is-active --quiet ssh; then
    SSH_SERVICE="ssh"
elif systemctl is-active --quiet sshd; then
    SSH_SERVICE="sshd"
else
    SSH_SERVICE="ssh"  # Default to ssh for Ubuntu/Debian
fi

echo "SSH service detected: ${'$'}SSH_SERVICE"

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

sshd -t && systemctl reload ${'$'}SSH_SERVICE

# Install and configure Tinyproxy
${'$'}PKG_UPDATE
${'$'}PKG_INSTALL tinyproxy

# Configure Tinyproxy
sed -i 's/^Port.*/Port 8118/' /etc/tinyproxy/tinyproxy.conf
sed -i 's/^Listen.*/Listen 127.0.0.1/' /etc/tinyproxy/tinyproxy.conf
sed -i 's/^#Allow 127.0.0.1/Allow 127.0.0.1/' /etc/tinyproxy/tinyproxy.conf
systemctl restart tinyproxy && systemctl enable tinyproxy
echo "Tinyproxy configured on port 8118"

echo "Setup complete! Test: ssh -N -L 8080:127.0.0.1:8118 user@your-server"
SCRIPT
)
        """.trimIndent()
    }
    
    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Server Instructions", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Instructions copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}
