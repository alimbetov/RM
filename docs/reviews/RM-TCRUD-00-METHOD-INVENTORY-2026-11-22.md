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
