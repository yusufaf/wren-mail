package com.fsck.k9.mail.store.imap

import com.fsck.k9.mail.FolderType
import net.thunderbird.feature.mail.folder.api.FolderPathDelimiter

data class FolderListItem(
    val serverId: String,
    val name: String,
    val type: FolderType,
    val folderPathDelimiter: FolderPathDelimiter? = null,
    // Wren patch: the raw LIST attributes, so callers can tell \Archive from \All,
    // which `type` folds together; see mail/NOTICE.md.
    val attributes: List<String> = emptyList(),
)
