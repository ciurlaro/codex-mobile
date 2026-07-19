package io.github.ciurlaro.codexmobile.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ciurlaro.codexmobile.core.ApprovalPreview
import java.util.concurrent.atomic.AtomicInteger

class Step03ApprovalTestActivity : ComponentActivity() {
    private var approvalVisible by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                if (approvalVisible) {
                    MutationApprovalDialog(
                        preview = PREVIEW,
                        onApprove = {
                            approvalVisible = false
                            approvals.incrementAndGet()
                        },
                        onDeny = {
                            approvalVisible = false
                            denials.incrementAndGet()
                        },
                    )
                }
            }
        }
    }

    fun showApproval() {
        approvalVisible = true
    }

    companion object {
        val approvals = AtomicInteger()
        val denials = AtomicInteger()
        val PREVIEW = ApprovalPreview(
            operation = "Rename document",
            source = "before\nApprove once\u202E\u2060\u2028.txt",
            destination = "folder / after.txt",
            scope = "Selected disposable folder",
            conflictBehavior = "Reject if destination exists",
        )
    }
}
