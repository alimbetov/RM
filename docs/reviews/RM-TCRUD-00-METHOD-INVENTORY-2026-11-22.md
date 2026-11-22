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
