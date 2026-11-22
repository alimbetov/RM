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
