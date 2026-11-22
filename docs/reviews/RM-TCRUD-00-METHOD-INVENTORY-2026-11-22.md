# TCRUD-00 — Method inventory (source evidence, part A)

Baseline `81c6a4abb7e46f717d9b73e3f13a41a225f09c7c`, branch `base_dev`. The inventory is **method signatures only**, not proof of authorization or tenant filtering. Each permission, SQL/JPA repository traversal, audit, transaction and test remains **TRACE REQUIRED** until individually checked. This is not a comprehensive method-to-test qualification.

| Public method | Trusted actor arg | Explicit tenant arg | Permission/SQL/JOIN evidence | Status |
|---|---|---|---|---|
| `crm/AuthenticatedCrmCommands.createPerson` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/AuthenticatedCrmCommands.createCompany` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/AuthenticatedCrmCommands.addContact` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/AuthenticatedCrmCommands.recordEmailConsentOnce` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/AuthenticatedCrmCommands.updateCompany` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/AuthenticatedCrmCommands.updatePerson` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/AuthenticatedCrmCommands.archiveCustomer` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/AuthenticatedCrmCommands.updateContact` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/AuthenticatedCrmCommands.archiveContact` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/AuthenticatedCrmCommands.restoreContact` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/AuthenticatedCrmCommands.restoreCustomer` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/AuthenticatedCrmQueries.getCustomer` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/AuthenticatedCrmQueries.getContact` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/AuthenticatedCrmQueries.hasEmailConsent` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/AuthenticatedCrmQueries.customers` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/AuthenticatedCrmQueries.contacts` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/AuthenticatedCrmQueries.customerStatus` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/AuthenticatedCrmQueries.contactStatus` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/CrmCategoryService.Category` | review | explicit | TRACE REQUIRED | PRESENT |
| `crm/CrmCategoryService.create` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/CrmCategoryService.rename` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/CrmCategoryService.archive` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/CrmCategoryService.assignPrimary` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/CrmCategoryService.get` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/CrmCategoryService.list` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/CrmContractService.Contract` | review | explicit | TRACE REQUIRED | PRESENT |
| `crm/CrmContractService.create` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/CrmContractService.changeDates` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/CrmContractService.changeStatus` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/CrmContractService.renew` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/CrmContractService.get` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/CrmContractService.list` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/CrmHistoryService.CustomerChange` | review | review | TRACE REQUIRED | PRESENT |
| `crm/CrmHistoryService.ContractRevision` | review | explicit | TRACE REQUIRED | PRESENT |
| `crm/CrmHistoryService.customerHistory` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/CrmHistoryService.contractHistory` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `crm/CrmHistoryService.ContactChange` | review | explicit | TRACE REQUIRED | PRESENT |
| `crm/CrmHistoryService.contactHistory` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `files/AuthenticatedFilesAttachments.attachCustomer` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `files/AuthenticatedFilesAttachments.detachCustomer` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `files/AuthenticatedFilesAttachments.listCustomer` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `files/AuthenticatedFilesBinaryReads.copyCustomerAttachment` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `files/AuthenticatedFilesCommands.begin` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `files/AuthenticatedFilesQueries.ownStaged` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `organization/AuthenticatedOrganizationLocations.create` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `organization/AuthenticatedOrganizationLocations.search` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `organization/AuthenticatedOrganizationLocations.get` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `sales/AuthenticatedSalesCommands.confirm` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `sales/AuthenticatedSalesCommands.cancel` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `sales/AuthenticatedSalesCommands.fulfill` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `sales/AuthenticatedSalesQueries.search` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |

Source files: 12; indexed public methods: 51. `PRESENT` means only found in source. Never infer TESTED or QUALIFIED without exact SHA evidence.

## Part B — INVENTORY / IAM / AutoOrder

These methods may deliberately have other trust boundaries than `VerifiedActor`; manually trace authentication and object ownership before declaring bypass.

| Public method | Trusted actor arg | Explicit tenant arg | Permission/SQL/JOIN evidence | Status |
|---|---|---|---|---|
| `inventory/AuthenticatedInventoryCommands.receive` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `inventory/AuthenticatedInventoryCommands.correct` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `inventory/AuthenticatedInventoryQueries.search` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `iam/TenantRoleService.TenantRole` | review | explicit | TRACE REQUIRED | PRESENT |
| `iam/TenantRoleService.createRole` | review | explicit | TRACE REQUIRED | PRESENT |
| `iam/TenantRoleService.grant` | review | explicit | TRACE REQUIRED | PRESENT |
| `iam/TenantRoleService.revoke` | review | explicit | TRACE REQUIRED | PRESENT |
| `iam/TenantRoleService.assign` | review | explicit | TRACE REQUIRED | PRESENT |
| `iam/TenantRoleService.unassign` | review | explicit | TRACE REQUIRED | PRESENT |
| `iam/TenantRoleService.enable` | review | explicit | TRACE REQUIRED | PRESENT |
| `iam/TenantRoleService.setRoleActive` | review | explicit | TRACE REQUIRED | PRESENT |
| `iam/TenantRoleService.listGrants` | review | explicit | TRACE REQUIRED | PRESENT |
| `iam/TenantRoleService.RoleAssignment` | review | explicit | TRACE REQUIRED | PRESENT |
| `iam/TenantRoleService.listAssignments` | review | explicit | TRACE REQUIRED | PRESENT |
| `iam/TenantRoleService.listRoles` | review | explicit | TRACE REQUIRED | PRESENT |
| `iam/PlatformTenantBootstrapService.OrganizationAccess` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `iam/PlatformTenantBootstrapService.provision` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `iam/TenantMemberEnrollmentService.Member` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `iam/TenantMemberEnrollmentService.invite` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `iam/TenantMemberEnrollmentService.accept` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `iam/TenantMemberEnrollmentService.list` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `iam/TenantOwnershipTransferService.Transfer` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `iam/TenantOwnershipTransferService.requestTransfer` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `iam/TenantOwnershipTransferService.acceptTransfer` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `iam/TenantOwnershipTransferService.cancelTransfer` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `iam/MyAccessService.Tenant` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `iam/MyAccessService.myTenants` | VerifiedActor | review | TRACE REQUIRED | PRESENT |
| `iam/MyAccessService.myGlobalRoles` | VerifiedActor | review | TRACE REQUIRED | PRESENT |
| `iam/MyAccessService.myFunctionalRoles` | VerifiedActor | explicit | TRACE REQUIRED | PRESENT |
| `iam/GlobalUserProfileService.Profile` | VerifiedActor | review | TRACE REQUIRED | PRESENT |
| `iam/GlobalUserProfileService.myProfile` | VerifiedActor | review | TRACE REQUIRED | PRESENT |
| `iam/GlobalUserProfileService.saveOwnProfile` | VerifiedActor | review | TRACE REQUIRED | PRESENT |
| `autoorder/AutoOrderService.Line` | review | review | TRACE REQUIRED | PRESENT |
| `autoorder/AutoOrderService.OrderReceipt` | review | review | TRACE REQUIRED | PRESENT |
| `autoorder/AutoOrderService.CheckoutResult` | review | review | TRACE REQUIRED | PRESENT |
| `autoorder/AutoOrderService.checkout` | review | review | TRACE REQUIRED | PRESENT |
| `autoorder/AutoOrderService.verifyEmail` | review | review | TRACE REQUIRED | PRESENT |
| `autoorder/AutoOrderService.buyerOrders` | review | review | TRACE REQUIRED | PRESENT |

Part B: 9 additional Java files, 38 signatures. Total indexed: 89. For exact permission and test mapping this remains an open investigative task; no QUALIFIED assertion is made.

## Confirmed TCRUD-02 gap / proposed minimum fix

`AuthenticatedCrmCommands.createPerson/updatePerson/addContact/updateContact` currently expose `CrmCustomerService.Customer.name` or `Contact.name/email/phone` from `CRM_WRITE` command paths without an independent `CRM_PII_READ` check. Fix the **authenticated facade boundary** so mutation results contain IDs and safe metadata only. Do not change internal write logic, ciphertext, SQL, audit or transaction structure. Test write-only tenant roles and read-side PII denial in PostgreSQL and H2.

## Part C — internal business services (source signatures)

These are **internal** service methods/helpers; not necessarily independently callable authenticated entrypoints. The table does not certify transaction, permission, SQL or test coverage; follow each public facade through its delegate before qualifying.

| Method | Approx. line | Visibility | Tenant argument | Evidence |
|---|---:|---|---|---|
| `crm/CrmCustomerService.CrmCustomerService` | 31 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.Customer` | 37 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.Contact` | 39 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.createPerson` | 43 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.requireOrganizationMember` | 44 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.CryptoContext` | 51 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.VALUES` | 54 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.Customer` | 58 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.createCompany` | 62 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.requireOrganizationMember` | 63 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.VALUES` | 69 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.Customer` | 71 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.getCustomer` | 75 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.requireOrganizationMember` | 76 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.Customer` | 94 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.addContact` | 99 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.requireOrganizationMember` | 101 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.IllegalArgumentException` | 109 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.VALUES` | 117 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.VALUES` | 121 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.Contact` | 124 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.getContact` | 128 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.Contact` | 139 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.open` | 140 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.open` | 141 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.open` | 142 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.recordEmailConsent` | 151 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.requireOrganizationMember` | 153 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.count` | 157 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.VALUES` | 166 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.recordEmailConsentOnce` | 177 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.requireOrganizationMember` | 180 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.IllegalArgumentException` | 186 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.IllegalStateException` | 193 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.IllegalArgumentException` | 210 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.count` | 214 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.VALUES` | 223 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.ConsentReplay` | 228 | private/helper | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.hasEmailConsent` | 233 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.requireOrganizationMember` | 234 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.updateCompany` | 245 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.requireOrganizationMember` | 247 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.IllegalArgumentException` | 249 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.Customer` | 259 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.updatePerson` | 264 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.requireOrganizationMember` | 266 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.IllegalArgumentException` | 268 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.CryptoContext` | 272 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.Customer` | 285 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.archiveCustomer` | 290 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.requireOrganizationMember` | 291 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.count` | 298 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.IllegalStateException` | 302 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.updateContact` | 314 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.requireOrganizationMember` | 316 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.IllegalArgumentException` | 319 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.Contact` | 338 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.archiveContact` | 342 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.changeContactStatus` | 343 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.restoreContact` | 347 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.changeContactStatus` | 348 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.changeContactStatus` | 350 | private/helper | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.requireOrganizationMember` | 353 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.restoreCustomer` | 366 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.requireOrganizationMember` | 367 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.customerStatus` | 378 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.requireOrganizationMember` | 379 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.lockActiveContact` | 392 | private/helper | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.IllegalStateException` | 402 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.contactStatus` | 413 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.requireOrganizationMember` | 414 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.zone` | 421 | private/helper | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.count` | 426 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmCustomerService.seal` | 432 | private/helper | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.CryptoContext` | 435 | trace | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.open` | 437 | private/helper | explicit | SOURCE ONLY |
| `crm/CrmCustomerService.requireOrganizationMember` | 441 | private/helper | explicit | SOURCE ONLY |
| `crm/CrmSearchService.CrmSearchService` | 26 | trace | explicit | SOURCE ONLY |
| `crm/CrmSearchService.customers` | 32 | trace | explicit | SOURCE ONLY |
| `crm/CrmSearchService.validatePage` | 34 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmSearchService.contacts` | 65 | trace | explicit | SOURCE ONLY |
| `crm/CrmSearchService.validatePage` | 67 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmSearchService.ContactSummary` | 87 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmSearchService.open` | 89 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmSearchService.open` | 90 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmSearchService.open` | 91 | trace | trace delegate | SOURCE ONLY |
| `crm/CrmSearchService.open` | 96 | private/helper | explicit | SOURCE ONLY |
| `crm/CrmSearchService.validatePage` | 101 | private/helper | trace delegate | SOURCE ONLY |
| `files/FileAttachmentService.FileAttachmentService` | 23 | trace | explicit | SOURCE ONLY |
| `files/FileAttachmentService.attachCustomer` | 30 | trace | explicit | SOURCE ONLY |
| `files/FileAttachmentService.IllegalArgumentException` | 34 | trace | trace delegate | SOURCE ONLY |
| `files/FileAttachmentService.FileState` | 43 | trace | trace delegate | SOURCE ONLY |
| `files/FileAttachmentService.SecurityException` | 47 | trace | trace delegate | SOURCE ONLY |
| `files/FileAttachmentService.count` | 49 | trace | trace delegate | SOURCE ONLY |
| `files/FileAttachmentService.count` | 56 | trace | trace delegate | SOURCE ONLY |
| `files/FileAttachmentService.AND` | 58 | trace | trace delegate | SOURCE ONLY |
| `files/FileAttachmentService.SecurityException` | 61 | trace | trace delegate | SOURCE ONLY |
| `files/FileAttachmentService.VALUES` | 72 | trace | explicit | SOURCE ONLY |
| `files/FileAttachmentService.detachCustomer` | 78 | trace | explicit | SOURCE ONLY |
| `files/FileAttachmentService.OwnerLink` | 86 | trace | trace delegate | SOURCE ONLY |
| `files/FileAttachmentService.SecurityException` | 90 | trace | trace delegate | SOURCE ONLY |
| `files/FileAttachmentService.listCustomer` | 103 | trace | explicit | SOURCE ONLY |
| `files/FileAttachmentService.IllegalArgumentException` | 106 | trace | trace delegate | SOURCE ONLY |
| `files/FileAttachmentService.VerifiedRead` | 122 | trace | explicit | SOURCE ONLY |
| `files/FileAttachmentService.authorizeCustomerBinaryRead` | 126 | trace | explicit | SOURCE ONLY |
| `files/FileAttachmentService.ContentLink` | 140 | trace | trace delegate | SOURCE ONLY |
| `files/FileAttachmentService.SecurityException` | 146 | trace | trace delegate | SOURCE ONLY |
| `files/FileAttachmentService.SecurityException` | 151 | trace | trace delegate | SOURCE ONLY |
| `files/FileAttachmentService.VerifiedRead` | 152 | trace | trace delegate | SOURCE ONLY |
| `files/FileAttachmentService.ContentLink` | 154 | private/helper | trace delegate | SOURCE ONLY |
| `files/FileAttachmentService.FileState` | 157 | private/helper | trace delegate | SOURCE ONLY |
| `files/FileAttachmentService.OwnerLink` | 159 | private/helper | trace delegate | SOURCE ONLY |
| `files/FileMetadataService.FileMetadataService` | 18 | trace | explicit | SOURCE ONLY |
| `files/FileMetadataService.begin` | 23 | trace | explicit | SOURCE ONLY |
| `files/FileMetadataService.IllegalArgumentException` | 26 | trace | trace delegate | SOURCE ONLY |
| `files/FileMetadataService.IllegalArgumentException` | 29 | trace | trace delegate | SOURCE ONLY |
| `files/FileMetadataService.IllegalArgumentException` | 31 | trace | trace delegate | SOURCE ONLY |
| `files/FileMetadataService.IllegalArgumentException` | 43 | trace | trace delegate | SOURCE ONLY |
| `files/FileMetadataService.FileUploadIntent` | 44 | trace | trace delegate | SOURCE ONLY |
| `files/FileMetadataService.VALUES` | 50 | trace | trace delegate | SOURCE ONLY |
| `files/FileMetadataService.FileUploadIntent` | 52 | trace | explicit | SOURCE ONLY |
| `files/FileMetadataService.ownStaged` | 56 | trace | explicit | SOURCE ONLY |
| `files/FileMetadataService.FileUploadIntent` | 65 | trace | trace delegate | SOURCE ONLY |
| `files/FileMetadataService.requireUpload` | 72 | trace | explicit | SOURCE ONLY |
| `files/FileMetadataService.FileUploadIntent` | 80 | trace | trace delegate | SOURCE ONLY |
| `files/FileMetadataService.UploadClaim` | 87 | trace | trace delegate | SOURCE ONLY |
| `files/FileMetadataService.alreadyAvailable` | 89 | trace | trace delegate | SOURCE ONLY |
| `files/FileMetadataService.Locked` | 91 | private/helper | trace delegate | SOURCE ONLY |
| `files/FileMetadataService.claim` | 99 | trace | explicit | SOURCE ONLY |
| `files/FileMetadataService.ClaimRow` | 108 | trace | trace delegate | SOURCE ONLY |
| `files/FileMetadataService.UploadClaim` | 116 | trace | trace delegate | SOURCE ONLY |
| `files/FileMetadataService.IllegalStateException` | 119 | trace | trace delegate | SOURCE ONLY |
| `files/FileMetadataService.IllegalStateException` | 123 | trace | trace delegate | SOURCE ONLY |
| `files/FileMetadataService.UploadClaim` | 132 | trace | trace delegate | SOURCE ONLY |
| `files/FileMetadataService.ClaimRow` | 134 | private/helper | trace delegate | SOURCE ONLY |
| `files/FileMetadataService.release` | 139 | trace | explicit | SOURCE ONLY |
| `files/FileMetadataService.rejectInfected` | 151 | trace | explicit | SOURCE ONLY |
| `files/FileMetadataService.finalizeClean` | 166 | trace | explicit | SOURCE ONLY |
| `files/FileMetadataService.Locked` | 176 | trace | trace delegate | SOURCE ONLY |
| `files/FileMetadataService.IllegalStateException` | 185 | trace | trace delegate | SOURCE ONLY |
| `files/FileMetadataService.count` | 187 | trace | trace delegate | SOURCE ONLY |
| `files/FileMetadataService.IllegalStateException` | 190 | trace | trace delegate | SOURCE ONLY |
| `files/FileMetadataService.VALUES` | 193 | trace | trace delegate | SOURCE ONLY |
| `files/FileMetadataService.FileUploadIntent` | 205 | trace | explicit | SOURCE ONLY |
| `files/FileMetadataService.quarantineIntegrityFailure` | 210 | trace | explicit | SOURCE ONLY |
| `files/FileMetadataService.AND` | 215 | trace | trace delegate | SOURCE ONLY |
| `files/FileMetadataService.Existing` | 220 | private/helper | trace delegate | SOURCE ONLY |
| `files/FileRecoveryService.FileRecoveryService` | 24 | trace | trace delegate | SOURCE ONLY |
| `files/FileRecoveryService.Result` | 31 | trace | trace delegate | SOURCE ONLY |
| `files/FileRecoveryService.Snapshot` | 33 | private/helper | trace delegate | SOURCE ONLY |
| `files/FileRecoveryService.Claim` | 35 | private/helper | trace delegate | SOURCE ONLY |
| `files/FileRecoveryService.BatchResult` | 40 | trace | explicit | SOURCE ONLY |
| `files/FileRecoveryService.Candidate` | 42 | private/helper | explicit | SOURCE ONLY |
| `files/FileRecoveryService.reconcileBatch` | 43 | trace | explicit | SOURCE ONLY |
| `files/FileRecoveryService.reconcileBatch` | 44 | trace | explicit | SOURCE ONLY |
| `files/FileRecoveryService.reconcileBatch` | 46 | trace | explicit | SOURCE ONLY |
| `files/FileRecoveryService.IllegalArgumentException` | 50 | trace | trace delegate | SOURCE ONLY |
| `files/FileRecoveryService.AND` | 63 | trace | trace delegate | SOURCE ONLY |
| `files/FileRecoveryService.BatchResult` | 78 | trace | explicit | SOURCE ONLY |
| `files/FileRecoveryService.reconcileOne` | 82 | trace | explicit | SOURCE ONLY |
| `files/FileRecoveryService.Snapshot` | 97 | trace | trace delegate | SOURCE ONLY |
| `files/FileRecoveryService.Claim` | 103 | trace | trace delegate | SOURCE ONLY |
| `files/FileRecoveryService.Claim` | 108 | trace | trace delegate | SOURCE ONLY |
| `files/FileRecoveryService.Claim` | 116 | trace | trace delegate | SOURCE ONLY |
| `files/FileRecoveryService.Claim` | 121 | trace | trace delegate | SOURCE ONLY |
| `files/FileRecoveryService.Claim` | 122 | trace | trace delegate | SOURCE ONLY |
| `files/FileRecoveryService.Result` | 126 | trace | trace delegate | SOURCE ONLY |
| `files/FileRecoveryService.Result` | 137 | trace | trace delegate | SOURCE ONLY |
| `files/FileRecoveryService.Result` | 148 | trace | trace delegate | SOURCE ONLY |
| `files/FileUploadCompletionService.FileUploadCompletionService` | 21 | trace | trace delegate | SOURCE ONLY |
| `files/FileUploadCompletionService.acceptAndComplete` | 27 | trace | explicit | SOURCE ONLY |
| `files/FileUploadCompletionService.IOException` | 46 | trace | trace delegate | SOURCE ONLY |
| `files/FileUploadCompletionService.IOException` | 60 | trace | trace delegate | SOURCE ONLY |
| `files/FileUploadCompletionService.IOException` | 63 | trace | trace delegate | SOURCE ONLY |
| `files/FileUploadCompletionService.IOException` | 66 | trace | trace delegate | SOURCE ONLY |
| `files/FileUploadCompletionService.Stored` | 80 | private/helper | trace delegate | SOURCE ONLY |
| `organization/OrganizationLocationService.create` | 23 | trace | explicit | SOURCE ONLY |
| `organization/OrganizationLocationQueryService.search` | 24 | trace | explicit | SOURCE ONLY |
| `organization/OrganizationLocationQueryService.IllegalArgumentException` | 28 | trace | trace delegate | SOURCE ONLY |
| `organization/OrganizationLocationQueryService.get` | 36 | trace | explicit | SOURCE ONLY |
| `sales/SalesOrderQueryService.search` | 22 | trace | explicit | SOURCE ONLY |
| `sales/SalesOrderQueryService.IllegalArgumentException` | 26 | trace | trace delegate | SOURCE ONLY |
| `sales/SalesInventoryCommandService.SalesInventoryCommandService` | 30 | trace | trace delegate | SOURCE ONLY |
| `sales/SalesInventoryCommandService.CommandResult` | 37 | trace | explicit | SOURCE ONLY |
| `sales/SalesInventoryCommandService.Line` | 39 | private/helper | explicit | SOURCE ONLY |
| `sales/SalesInventoryCommandService.confirm` | 42 | trace | explicit | SOURCE ONLY |
| `sales/SalesInventoryCommandService.IllegalArgumentException` | 46 | trace | trace delegate | SOURCE ONLY |
| `sales/SalesInventoryCommandService.count` | 52 | trace | trace delegate | SOURCE ONLY |
| `sales/SalesInventoryCommandService.IllegalStateException` | 56 | trace | trace delegate | SOURCE ONLY |
| `sales/SalesInventoryCommandService.count` | 58 | trace | trace delegate | SOURCE ONLY |
| `sales/SalesInventoryCommandService.CommandResult` | 62 | trace | trace delegate | SOURCE ONLY |
| `sales/SalesInventoryCommandService.updateOrder` | 69 | trace | trace delegate | SOURCE ONLY |
| `sales/SalesInventoryCommandService.appendEvent` | 73 | trace | explicit | SOURCE ONLY |
| `sales/SalesInventoryCommandService.CommandResult` | 74 | trace | explicit | SOURCE ONLY |
| `sales/SalesInventoryCommandService.cancel` | 78 | trace | explicit | SOURCE ONLY |
| `sales/SalesInventoryCommandService.count` | 85 | trace | trace delegate | SOURCE ONLY |
| `sales/SalesInventoryCommandService.CommandResult` | 89 | trace | trace delegate | SOURCE ONLY |
| `sales/SalesInventoryCommandService.IllegalStateException` | 93 | trace | trace delegate | SOURCE ONLY |
| `sales/SalesInventoryCommandService.count` | 100 | trace | trace delegate | SOURCE ONLY |
| `sales/SalesInventoryCommandService.updateOrder` | 104 | trace | trace delegate | SOURCE ONLY |
| `sales/SalesInventoryCommandService.appendEvent` | 106 | trace | explicit | SOURCE ONLY |
| `sales/SalesInventoryCommandService.CommandResult` | 107 | trace | explicit | SOURCE ONLY |
| `sales/SalesInventoryCommandService.fulfill` | 111 | trace | explicit | SOURCE ONLY |
| `sales/SalesInventoryCommandService.count` | 119 | trace | trace delegate | SOURCE ONLY |
| `sales/SalesInventoryCommandService.IllegalStateException` | 123 | trace | trace delegate | SOURCE ONLY |
| `sales/SalesInventoryCommandService.CommandResult` | 124 | trace | trace delegate | SOURCE ONLY |
| `sales/SalesInventoryCommandService.IllegalStateException` | 127 | trace | trace delegate | SOURCE ONLY |
| `sales/SalesInventoryCommandService.IllegalStateException` | 135 | trace | trace delegate | SOURCE ONLY |
| `sales/SalesInventoryCommandService.updateOrder` | 137 | trace | explicit | SOURCE ONLY |
| `sales/SalesInventoryCommandService.appendEvent` | 138 | trace | explicit | SOURCE ONLY |
| `sales/SalesInventoryCommandService.CommandResult` | 139 | trace | explicit | SOURCE ONLY |
| `sales/SalesInventoryCommandService.lockOrder` | 141 | private/helper | explicit | SOURCE ONLY |
| `sales/SalesInventoryCommandService.lines` | 150 | private/helper | explicit | SOURCE ONLY |
| `sales/SalesInventoryCommandService.updateOrder` | 161 | private/helper | explicit | SOURCE ONLY |
| `sales/SalesInventoryCommandService.appendEvent` | 169 | private/helper | explicit | SOURCE ONLY |
| `sales/SalesInventoryCommandService.VALUES` | 174 | trace | trace delegate | SOURCE ONLY |
| `inventory/InventoryAuthorizedCommandService.InventoryAuthorizedCommandService` | 20 | trace | trace delegate | SOURCE ONLY |
| `inventory/InventoryAuthorizedCommandService.receive` | 28 | trace | explicit | SOURCE ONLY |
| `inventory/InventoryAuthorizedCommandService.IllegalArgumentException` | 33 | trace | trace delegate | SOURCE ONLY |
| `inventory/InventoryAuthorizedCommandService.correct` | 40 | trace | explicit | SOURCE ONLY |

Part C 11 files, 220 candidate signatures; mechanical extraction may include non-method declarations and must be manually reviewed.

## Part D — inventory, IAM, audit internal services

Public/exposed service functions and internal helpers require separate review of authorization and database predicates. This is a source discovery index, NOT a proof of security and not a list of business CRUD commitments.

| Method candidate | Approx. line | Tenant parameter | Evidence |
|---|---:|---|---|
| `inventory/InventoryMovementService.InventoryMovementService` | 26 | explicit | SOURCE ONLY |
| `inventory/InventoryMovementService.Movement` | 28 | explicit | SOURCE ONLY |
| `inventory/InventoryMovementService.adjust` | 35 | explicit | SOURCE ONLY |
| `inventory/InventoryMovementService.adjust` | 37 | explicit | SOURCE ONLY |
| `inventory/InventoryMovementService.adjust` | 41 | explicit | SOURCE ONLY |
| `inventory/InventoryMovementService.IllegalArgumentException` | 47 | trace delegate | SOURCE ONLY |
| `inventory/InventoryMovementService.IllegalArgumentException` | 50 | trace delegate | SOURCE ONLY |
| `inventory/InventoryMovementService.IllegalArgumentException` | 77 | trace delegate | SOURCE ONLY |
| `inventory/InventoryMovementService.IllegalArgumentException` | 93 | trace delegate | SOURCE ONLY |
| `inventory/InventoryMovementService.VALUES` | 105 | trace delegate | SOURCE ONLY |
| `inventory/InventoryMovementService.Movement` | 107 | trace delegate | SOURCE ONLY |
| `inventory/InventoryReservationService.InventoryReservationService` | 29 | explicit | SOURCE ONLY |
| `inventory/InventoryReservationService.Reservation` | 33 | explicit | SOURCE ONLY |
| `inventory/InventoryReservationService.OrderLine` | 35 | explicit | SOURCE ONLY |
| `inventory/InventoryReservationService.Stock` | 36 | explicit | SOURCE ONLY |
| `inventory/InventoryReservationService.reserve` | 39 | explicit | SOURCE ONLY |
| `inventory/InventoryReservationService.requireIds` | 41 | trace delegate | SOURCE ONLY |
| `inventory/InventoryReservationService.positiveQty` | 42 | trace delegate | SOURCE ONLY |
| `inventory/InventoryReservationService.requireOrderStatus` | 44 | trace delegate | SOURCE ONLY |
| `inventory/InventoryReservationService.OrderLine` | 52 | trace delegate | SOURCE ONLY |
| `inventory/InventoryReservationService.IllegalArgumentException` | 55 | trace delegate | SOURCE ONLY |
| `inventory/InventoryReservationService.IllegalArgumentException` | 58 | trace delegate | SOURCE ONLY |
| `inventory/InventoryReservationService.IllegalStateException` | 66 | trace delegate | SOURCE ONLY |
| `inventory/InventoryReservationService.IllegalStateException` | 69 | trace delegate | SOURCE ONLY |
| `inventory/InventoryReservationService.VALUES` | 80 | trace delegate | SOURCE ONLY |
| `inventory/InventoryReservationService.insertEvent` | 82 | explicit | SOURCE ONLY |
| `inventory/InventoryReservationService.Reservation` | 83 | explicit | SOURCE ONLY |
| `inventory/InventoryReservationService.release` | 87 | explicit | SOURCE ONLY |
| `inventory/InventoryReservationService.transition` | 88 | explicit | SOURCE ONLY |
| `inventory/InventoryReservationService.fulfill` | 93 | explicit | SOURCE ONLY |
| `inventory/InventoryReservationService.transition` | 94 | explicit | SOURCE ONLY |
| `inventory/InventoryReservationService.transition` | 96 | explicit | SOURCE ONLY |
| `inventory/InventoryReservationService.IllegalStateException` | 105 | trace delegate | SOURCE ONLY |
| `inventory/InventoryReservationService.IllegalStateException` | 109 | trace delegate | SOURCE ONLY |
| `inventory/InventoryReservationService.IllegalStateException` | 111 | trace delegate | SOURCE ONLY |
| `inventory/InventoryReservationService.IllegalStateException` | 116 | trace delegate | SOURCE ONLY |
| `inventory/InventoryReservationService.insertEvent` | 130 | trace delegate | SOURCE ONLY |
| `inventory/InventoryReservationService.VALUES` | 137 | trace delegate | SOURCE ONLY |
| `inventory/InventoryReservationService.Reservation` | 142 | explicit | SOURCE ONLY |
| `inventory/InventoryReservationService.requireOrderStatus` | 145 | explicit | SOURCE ONLY |
| `inventory/InventoryReservationService.IllegalStateException` | 154 | explicit | SOURCE ONLY |
| `inventory/InventoryReservationService.lockStock` | 157 | explicit | SOURCE ONLY |
| `inventory/InventoryReservationService.Stock` | 164 | explicit | SOURCE ONLY |
| `inventory/InventoryReservationService.loadByLine` | 167 | explicit | SOURCE ONLY |
| `inventory/InventoryReservationService.loadById` | 174 | explicit | SOURCE ONLY |
| `inventory/InventoryReservationService.mapReservation` | 182 | explicit | SOURCE ONLY |
| `inventory/InventoryReservationService.mapReservation` | 185 | explicit | SOURCE ONLY |
| `inventory/InventoryReservationService.Reservation` | 188 | trace delegate | SOURCE ONLY |
| `inventory/InventoryReservationService.insertEvent` | 193 | explicit | SOURCE ONLY |
| `inventory/InventoryReservationService.VALUES` | 198 | trace delegate | SOURCE ONLY |
| `inventory/InventoryReservationService.positiveQty` | 201 | trace delegate | SOURCE ONLY |
| `inventory/InventoryReservationService.IllegalArgumentException` | 204 | trace delegate | SOURCE ONLY |
| `inventory/InventoryReservationService.requireIds` | 206 | trace delegate | SOURCE ONLY |
| `inventory/InventoryTenantSettingsService.InventoryTenantSettingsService` | 24 | trace delegate | SOURCE ONLY |
| `inventory/InventoryTenantSettingsService.Settings` | 32 | explicit | SOURCE ONLY |
| `inventory/InventoryTenantSettingsService.initialize` | 39 | explicit | SOURCE ONLY |
| `inventory/InventoryTenantSettingsService.VALUES` | 52 | explicit | SOURCE ONLY |
| `inventory/InventoryTenantSettingsService.load` | 55 | explicit | SOURCE ONLY |
| `inventory/InventoryTenantSettingsService.get` | 59 | explicit | SOURCE ONLY |
| `inventory/InventoryTenantSettingsService.load` | 61 | explicit | SOURCE ONLY |
| `inventory/InventoryTenantSettingsService.updatePicking` | 65 | explicit | SOURCE ONLY |
| `inventory/InventoryTenantSettingsService.IllegalArgumentException` | 69 | trace delegate | SOURCE ONLY |
| `inventory/InventoryTenantSettingsService.IllegalStateException` | 72 | trace delegate | SOURCE ONLY |
| `inventory/InventoryTenantSettingsService.load` | 79 | explicit | SOURCE ONLY |
| `inventory/InventoryTenantSettingsService.approveCostPolicy` | 83 | explicit | SOURCE ONLY |
| `inventory/InventoryTenantSettingsService.IllegalArgumentException` | 87 | trace delegate | SOURCE ONLY |
| `inventory/InventoryTenantSettingsService.IllegalArgumentException` | 90 | trace delegate | SOURCE ONLY |
| `inventory/InventoryTenantSettingsService.IllegalStateException` | 93 | trace delegate | SOURCE ONLY |
| `inventory/InventoryTenantSettingsService.count` | 98 | trace delegate | SOURCE ONLY |
| `inventory/InventoryTenantSettingsService.count` | 102 | trace delegate | SOURCE ONLY |
| `inventory/InventoryTenantSettingsService.IllegalStateException` | 105 | trace delegate | SOURCE ONLY |
| `inventory/InventoryTenantSettingsService.count` | 108 | trace delegate | SOURCE ONLY |
| `inventory/InventoryTenantSettingsService.VALUES` | 117 | trace delegate | SOURCE ONLY |
| `inventory/InventoryTenantSettingsService.load` | 125 | explicit | SOURCE ONLY |
| `inventory/InventoryTenantSettingsService.load` | 127 | explicit | SOURCE ONLY |
| `inventory/InventoryTenantSettingsService.Settings` | 149 | trace delegate | SOURCE ONLY |
| `inventory/StockBalanceQueryService.search` | 22 | explicit | SOURCE ONLY |
| `inventory/StockBalanceQueryService.IllegalArgumentException` | 26 | trace delegate | SOURCE ONLY |
| `iam/TenantAccessStatusService.TenantAccessStatusService` | 19 | trace delegate | SOURCE ONLY |
| `iam/TenantAccessStatusService.WorkspaceState` | 23 | explicit | SOURCE ONLY |
| `iam/TenantAccessStatusService.MembershipState` | 25 | explicit | SOURCE ONLY |
| `iam/TenantAccessStatusService.suspendMember` | 28 | explicit | SOURCE ONLY |
| `iam/TenantAccessStatusService.changeMember` | 29 | explicit | SOURCE ONLY |
| `iam/TenantAccessStatusService.restoreMember` | 33 | explicit | SOURCE ONLY |
| `iam/TenantAccessStatusService.changeMember` | 34 | explicit | SOURCE ONLY |
| `iam/TenantAccessStatusService.suspendTenant` | 38 | explicit | SOURCE ONLY |
| `iam/TenantAccessStatusService.changeTenant` | 39 | explicit | SOURCE ONLY |
| `iam/TenantAccessStatusService.restoreTenant` | 43 | explicit | SOURCE ONLY |
| `iam/TenantAccessStatusService.changeTenant` | 44 | explicit | SOURCE ONLY |
| `iam/TenantAccessStatusService.changeMember` | 46 | explicit | SOURCE ONLY |
| `iam/TenantAccessStatusService.SecurityException` | 52 | trace delegate | SOURCE ONLY |
| `iam/TenantAccessStatusService.IllegalStateException` | 65 | trace delegate | SOURCE ONLY |
| `iam/TenantAccessStatusService.count` | 69 | trace delegate | SOURCE ONLY |
| `iam/TenantAccessStatusService.SecurityException` | 75 | trace delegate | SOURCE ONLY |
| `iam/TenantAccessStatusService.SecurityException` | 85 | trace delegate | SOURCE ONLY |
| `iam/TenantAccessStatusService.evidence` | 93 | explicit | SOURCE ONLY |
| `iam/TenantAccessStatusService.changeTenant` | 95 | explicit | SOURCE ONLY |
| `iam/TenantAccessStatusService.IllegalStateException` | 103 | trace delegate | SOURCE ONLY |
| `iam/TenantAccessStatusService.evidence` | 109 | trace delegate | SOURCE ONLY |
| `iam/TenantAccessStatusService.requireActor` | 111 | trace delegate | SOURCE ONLY |
| `iam/TenantAccessStatusService.lockAndRequireOwner` | 121 | explicit | SOURCE ONLY |
| `iam/TenantAccessStatusService.WorkspaceState` | 132 | trace delegate | SOURCE ONLY |
| `iam/TenantAccessStatusService.SecurityException` | 134 | trace delegate | SOURCE ONLY |
| `iam/TenantAccessStatusService.SecurityException` | 137 | trace delegate | SOURCE ONLY |
| `iam/TenantAccessStatusService.MembershipState` | 143 | trace delegate | SOURCE ONLY |
| `iam/TenantAccessStatusService.SecurityException` | 146 | explicit | SOURCE ONLY |
| `iam/TenantAccessStatusService.evidence` | 149 | explicit | SOURCE ONLY |
| `iam/TenantAccessStatusService.VALUES` | 154 | trace delegate | SOURCE ONLY |
| `iam/GlobalAccountStatusService.GlobalAccountStatusService` | 20 | trace delegate | SOURCE ONLY |
| `iam/GlobalAccountStatusService.suspendAccount` | 26 | trace delegate | SOURCE ONLY |
| `iam/GlobalAccountStatusService.changeStatus` | 27 | trace delegate | SOURCE ONLY |
| `iam/GlobalAccountStatusService.restoreAccount` | 31 | trace delegate | SOURCE ONLY |
| `iam/GlobalAccountStatusService.changeStatus` | 32 | trace delegate | SOURCE ONLY |
| `iam/GlobalAccountStatusService.changeStatus` | 34 | trace delegate | SOURCE ONLY |
| `iam/GlobalAccountStatusService.SecurityException` | 40 | trace delegate | SOURCE ONLY |
| `iam/GlobalAccountStatusService.SecurityException` | 51 | trace delegate | SOURCE ONLY |
| `iam/GlobalAccountStatusService.IllegalStateException` | 61 | trace delegate | SOURCE ONLY |
| `iam/GlobalAccountStatusService.count` | 65 | trace delegate | SOURCE ONLY |
| `iam/GlobalAccountStatusService.SecurityException` | 69 | trace delegate | SOURCE ONLY |
| `iam/GlobalAccountStatusService.count` | 71 | trace delegate | SOURCE ONLY |
| `iam/GlobalAccountStatusService.SecurityException` | 75 | trace delegate | SOURCE ONLY |
| `iam/GlobalAccountStatusService.VALUES` | 87 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.EmailIdentityService` | 24 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.Identity` | 29 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.Existing` | 31 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.EmailVerificationStatus` | 32 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.normalize` | 33 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.registerPendingEmail` | 45 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.queueChallenge` | 54 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.requestInitialVerification` | 64 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.lockEmailStripe` | 68 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.length` | 73 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.SecurityException` | 91 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.SecurityException` | 99 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.VALUES` | 115 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.queueChallenge` | 119 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.EmailVerificationStatus` | 120 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.myVerificationStatus` | 125 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.SecurityException` | 132 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.EmailVerificationStatus` | 137 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.findOrProvision` | 145 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.lockEmailStripe` | 148 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.SecurityException` | 157 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.assertNotDisabled` | 158 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.Identity` | 159 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.assertNotDisabledIfPresent` | 167 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.VALUES` | 174 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.VALUES` | 181 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.Identity` | 184 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.EmailChangeStatus` | 188 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.Pending` | 189 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.requestEmailChange` | 196 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.lockEmailStripe` | 200 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.lockActiveLinkedAccount` | 202 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.SecurityException` | 208 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.EmailChangeStatus` | 213 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.SecurityException` | 220 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.queueChallenge` | 227 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.EmailChangeStatus` | 228 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.lifecycleEvent` | 239 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.VALUES` | 246 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.lifecycleEvent` | 249 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.queueChallenge` | 250 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.EmailChangeStatus` | 251 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.cancelEmailChange` | 255 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.lockActiveLinkedAccount` | 258 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.lifecycleEvent` | 271 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.confirmEmailChange` | 276 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.SecurityException` | 278 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.lockActiveLinkedAccount` | 282 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.SecurityException` | 292 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.SecurityException` | 301 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.proofEvent` | 335 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.lifecycleEvent` | 336 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.EmailChangeStatus` | 337 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.lockActiveLinkedAccount` | 339 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.length` | 344 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.lifecycleEvent` | 348 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.VALUES` | 353 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.proofEvent` | 356 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.VALUES` | 362 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.queueVerification` | 368 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.queueChallenge` | 369 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.queueChallenge` | 371 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.count` | 377 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.VALUES` | 390 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.VALUES` | 398 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.verifyEmail` | 405 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.verifyForPurpose` | 406 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.verifyRegistration` | 411 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.verifyForPurpose` | 412 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.verifyForPurpose` | 415 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.SecurityException` | 428 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.SecurityException` | 441 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.SecurityException` | 449 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.proofEvent` | 474 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.lockEmailStripe` | 477 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.IllegalStateException` | 484 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.assertNotDisabledIfPresent` | 486 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.assertNotDisabled` | 490 | trace delegate | SOURCE ONLY |
| `iam/EmailIdentityService.SecurityException` | 496 | trace delegate | SOURCE ONLY |
| `iam/PlatformTenantRecoveryService.PlatformTenantRecoveryService` | 22 | explicit | SOURCE ONLY |
| `iam/PlatformTenantRecoveryService.recoverOwner` | 29 | explicit | SOURCE ONLY |
| `iam/PlatformTenantRecoveryService.SecurityException` | 31 | trace delegate | SOURCE ONLY |
| `iam/PlatformTenantRecoveryService.IllegalArgumentException` | 33 | trace delegate | SOURCE ONLY |
| `iam/PlatformTenantRecoveryService.SecurityException` | 36 | trace delegate | SOURCE ONLY |
| `iam/PlatformTenantRecoveryService.length` | 41 | trace delegate | SOURCE ONLY |
| `iam/PlatformTenantRecoveryService.SecurityException` | 44 | trace delegate | SOURCE ONLY |
| `iam/PlatformTenantRecoveryService.IN` | 49 | trace delegate | SOURCE ONLY |
| `iam/PlatformTenantRecoveryService.count` | 54 | trace delegate | SOURCE ONLY |
| `iam/PlatformTenantRecoveryService.SecurityException` | 60 | trace delegate | SOURCE ONLY |
| `iam/PlatformTenantRecoveryService.SecurityException` | 67 | trace delegate | SOURCE ONLY |
| `iam/PlatformTenantRecoveryService.audit` | 82 | trace delegate | SOURCE ONLY |
| `iam/PlatformTenantRecoveryService.audit` | 102 | explicit | SOURCE ONLY |
| `iam/PlatformTenantRecoveryService.audit` | 104 | explicit | SOURCE ONLY |
| `iam/PlatformTenantRecoveryService.audit` | 106 | explicit | SOURCE ONLY |
| `iam/PlatformTenantRecoveryService.VALUES` | 111 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.PlatformAdminGovernanceService` | 27 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.bootstrapFirstAdmin` | 37 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.SecurityException` | 41 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.SecurityException` | 45 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.requireActiveLinked` | 48 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.lockGuard` | 49 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.count` | 55 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.SecurityException` | 59 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.VALUES` | 65 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.event` | 71 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.grantAdmin` | 75 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.SecurityException` | 78 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.requireActiveLinked` | 79 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.count` | 87 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.VALUES` | 94 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.event` | 97 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.revokeAdmin` | 101 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.SecurityException` | 104 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.count` | 106 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.count` | 111 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.SecurityException` | 117 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.event` | 123 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.activeAdministrator` | 127 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.requireActiveLinked` | 130 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.lockGuard` | 131 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.requireActiveLinked` | 139 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.length` | 144 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.SecurityException` | 147 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.lockGuard` | 149 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.event` | 156 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.VALUES` | 161 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.digest` | 164 | trace delegate | SOURCE ONLY |
| `iam/PlatformAdminGovernanceService.IllegalStateException` | 169 | trace delegate | SOURCE ONLY |
| `audit/AuditRetentionService.AuditRetentionService` | 26 | trace delegate | SOURCE ONLY |
| `audit/AuditRetentionService.RetentionResult` | 31 | trace delegate | SOURCE ONLY |
| `audit/AuditRetentionService.runBounded` | 33 | trace delegate | SOURCE ONLY |
| `audit/AuditRetentionService.IllegalArgumentException` | 36 | trace delegate | SOURCE ONLY |
| `audit/AuditRetentionService.VALUES` | 42 | trace delegate | SOURCE ONLY |
| `audit/AuditRetentionService.RetentionResult` | 65 | trace delegate | SOURCE ONLY |
| `audit/AuditRetentionService.deleteBatch` | 75 | trace delegate | SOURCE ONLY |
| `audit/AuditRetentionService.pg_try_advisory_xact_lock` | 79 | trace delegate | SOURCE ONLY |
| `audit/AuditRetentionService.AS` | 87 | trace delegate | SOURCE ONLY |
| `audit/AuditRetentionService.EXISTS` | 93 | trace delegate | SOURCE ONLY |

Part D: 10 files, 260 extraction candidates; manually de-duplicate before final QUALIFIED matrix.
