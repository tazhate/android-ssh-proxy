package com.example.sshproxy.ui.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.sshproxy.MainActivity
import com.example.sshproxy.databinding.FragmentKeyChoiceBinding

class KeyChoiceFragment : Fragment() {
    private var _binding: FragmentKeyChoiceBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentKeyChoiceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding.btnGenerate.setOnClickListener {
            (activity as? MainActivity)?.navigateToKeyGeneration()
        }

        binding.btnSkip.setOnClickListener {
            // Переход к добавлению сервера без генерации ключа
            (activity as? MainActivity)?.navigateToAddServer()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}