package dev.penrelay.app

import android.app.AlertDialog
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

internal class ConnectionProfiles(
    private val ui: PenRelayUi,
    private val library: ConnectionLibrary,
    private val settingsToSave: () -> ConnectionSettings?,
    private val onLoad: (ConnectionSettings) -> Unit
) {
    val actions = ui.row().apply {
        fun button(title: String, click: () -> Unit) {
            addView(ui.action(title, click).apply { contentDescription = "프로필 $title" },
                LinearLayout.LayoutParams(0, -2, 1f))
        }
        button("저장", ::saveProfile)
        button("불러오기") {
            choose("프로필 불러오기") { name ->
                onLoad(library.load(name))
                message("‘$name’ 프로필을 불러왔습니다. 확인 후 적용하세요.")
            }
        }
        button("삭제") { choose("삭제할 프로필 선택", ::confirmDelete) }
    }

    private fun choose(title: String, select: (String) -> Unit) {
        val names = library.names()
        val dialog = AlertDialog.Builder(ui.context).setTitle(title).setNegativeButton("닫기", null)
        if (names.isEmpty()) dialog.setMessage("저장한 프로필이 없습니다.")
        else dialog.setItems(names.toTypedArray()) { _, index -> select(names[index]) }
        dialog.show()
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(ui.context).setMessage("‘$name’ 프로필을 삭제할까요?")
            .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ ->
                library.delete(name)
                message("‘$name’ 프로필을 삭제했습니다.")
            }.show()
    }

    private fun saveProfile() {
        val settings = settingsToSave() ?: return
        val field = EditText(ui.context).apply { setSingleLine(); hint = "예: 집 PC" }
        val dialog = AlertDialog.Builder(ui.context).setTitle("연결 프로필 이름").setView(field)
            .setNegativeButton("취소", null).setPositiveButton("저장", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = field.text.toString().trim()
            if (name.isEmpty()) { field.error = "프로필 이름을 입력하세요."; return@setOnClickListener }
            fun save() {
                library.save(name, settings)
                dialog.dismiss()
                message("‘$name’ 프로필을 저장했습니다.")
            }
            if (name in library.names()) {
                AlertDialog.Builder(ui.context).setMessage("‘$name’ 프로필을 현재 설정으로 덮어쓸까요?")
                    .setPositiveButton("덮어쓰기") { _, _ -> save() }.setNegativeButton("취소", null).show()
            } else save()
        }
    }

    private fun message(text: String) = Toast.makeText(ui.context, text, Toast.LENGTH_SHORT).show()
}
