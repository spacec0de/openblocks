package com.openblocks.domain.organization.service;

import static com.openblocks.domain.organization.model.OrganizationState.ACTIVE;
import static com.openblocks.domain.organization.model.OrganizationState.DELETED;
import static com.openblocks.domain.util.QueryDslUtils.fieldName;
import static com.openblocks.sdk.exception.BizError.UNABLE_TO_FIND_VALID_ORG;
import static com.openblocks.sdk.util.ExceptionUtils.deferredError;
import static com.openblocks.sdk.util.ExceptionUtils.ofError;
import static com.openblocks.sdk.util.LocaleUtils.getLocale;
import static com.openblocks.sdk.util.LocaleUtils.getMessage;

import java.util.Collection;
import java.util.Locale;

import javax.annotation.Nonnull;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.codec.multipart.Part;
import org.springframework.stereotype.Service;

import com.openblocks.domain.asset.model.Asset;
import com.openblocks.domain.asset.service.AssetRepository;
import com.openblocks.domain.asset.service.AssetService;
import com.openblocks.domain.group.service.GroupService;
import com.openblocks.domain.organization.event.OrgDeletedEvent;
import com.openblocks.domain.organization.model.MemberRole;
import com.openblocks.domain.organization.model.Organization;
import com.openblocks.domain.organization.model.Organization.OrganizationCommonSettings;
import com.openblocks.domain.organization.model.OrganizationState;
import com.openblocks.domain.organization.model.QOrganization;
import com.openblocks.domain.organization.repository.OrganizationRepository;
import com.openblocks.domain.user.model.User;
import com.openblocks.infra.annotation.PossibleEmptyMono;
import com.openblocks.infra.mongo.MongoUpsertHelper;
import com.openblocks.sdk.config.CommonConfig;
import com.openblocks.sdk.config.dynamic.Conf;
import com.openblocks.sdk.config.dynamic.ConfigCenter;
import com.openblocks.sdk.constants.FieldName;
import com.openblocks.sdk.constants.WorkspaceMode;
import com.openblocks.sdk.exception.BizError;
import com.openblocks.sdk.exception.BizException;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class OrganizationServiceImpl implements OrganizationService {

    private final Conf<Integer> logoMaxSizeInKb;

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private AssetService assetService;

    @Autowired
    private OrgMemberService orgMemberService;

    @Autowired
    private MongoUpsertHelper mongoUpsertHelper;

    @Autowired
    private OrganizationRepository repository;

    @Autowired
    private GroupService groupService;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private CommonConfig commonConfig;

    @Autowired
    public OrganizationServiceImpl(ConfigCenter configCenter) {
        logoMaxSizeInKb = configCenter.asset().ofInteger("logoMaxSizeInKb", 300);
    }

    @Override
    public Mono<Organization> createDefault(User user) {
        return Mono.deferContextual(contextView -> {
            Locale locale = getLocale(contextView);
            String userOrgSuffix = getMessage(locale, "USER_ORG_SUFFIX");

            Organization organization = new Organization();
            organization.setName(user.getName() + userOrgSuffix);
            organization.setIsAutoGeneratedOrganization(true);
            // saas mode
            if (commonConfig.getWorkspace().getMode() == WorkspaceMode.SAAS) {
                return create(organization, user.getId());
            }
            // enterprise mode
            return joinOrganizationInEnterpriseMode(user.getId())
                    .flatMap(join -> {
                        if (Boolean.TRUE.equals(join)) {
                            return Mono.empty();
                        }
                        return create(organization, user.getId());
                    });
        });
    }

    private Mono<Boolean> joinOrganizationInEnterpriseMode(String userId) {
        return getOrganizationInEnterpriseMode()
                .flatMap(organization -> orgMemberService.addMember(organization.getId(), userId, MemberRole.MEMBER))
                .defaultIfEmpty(false);
    }

    @Override
    @PossibleEmptyMono
    public Mono<Organization> getOrganizationInEnterpriseMode() {
        if (commonConfig.getWorkspace().getMode() == WorkspaceMode.SAAS) {
            return Mono.empty();
        }
        return getByEnterpriseOrgId()
                .switchIfEmpty(repository.findFirstByStateMatches(ACTIVE));
    }

    @Nonnull
    private Mono<Organization> getByEnterpriseOrgId() {
        String enterpriseOrgId = commonConfig.getWorkspace().getEnterpriseOrgId();
        if (StringUtils.isBlank(enterpriseOrgId)) {
            return Mono.empty();
        }
        return repository.findById(enterpriseOrgId)
                .delayUntil(org -> {
                            if (org.getState() == DELETED) {
                                return ofError(BizError.ORG_DELETED_FOR_ENTERPRISE_MODE, "ORG_DELETED_FOR_ENTERPRISE_MODE");
                            }
                            return Mono.empty();
                        }
                );
    }

    @Override
    public Mono<Organization> create(Organization organization, String creatorId) {

        return Mono.defer(() -> {
                    if (organization == null || StringUtils.isNotBlank(organization.getId())) {
                        return Mono.error(new BizException(BizError.INVALID_PARAMETER, "INVALID_PARAMETER", FieldName.ORGANIZATION));
                    }
                    organization.setState(ACTIVE);
                    return Mono.just(organization);
                })
                .flatMap(repository::save)
                .flatMap(newOrg -> onOrgCreated(creatorId, newOrg))
                .log();
    }

    private Mono<Organization> onOrgCreated(String userId, Organization newOrg) {
        return groupService.createAllUserGroup(newOrg.getId())
                .then(groupService.createDevGroup(newOrg.getId()))
                .then(setOrgAdmin(userId, newOrg))
                .thenReturn(newOrg);
    }

    private Mono<Boolean> setOrgAdmin(String userId, Organization newOrg) {
        return orgMemberService.addMember(newOrg.getId(), userId, MemberRole.ADMIN);
    }

    @Override
    public Mono<Organization> getById(String id) {
        return repository.findByIdAndState(id, ACTIVE)
                .switchIfEmpty(deferredError(UNABLE_TO_FIND_VALID_ORG, "INVALID_ORG_ID"));
    }

    @Override
    public Mono<OrganizationCommonSettings> getOrgCommonSettings(String orgId) {
        return repository.findByIdAndState(orgId, ACTIVE)
                .switchIfEmpty(deferredError(UNABLE_TO_FIND_VALID_ORG, "INVALID_ORG_ID"))
                .map(Organization::getCommonSettings);
    }

    @Override
    public Flux<Organization> getByIds(Collection<String> ids) {
        return repository.findByIdInAndState(ids, ACTIVE);
    }

    @Override
    public Mono<Boolean> uploadLogo(String organizationId, Part filePart) {

        Mono<Asset> uploadAssetMono = assetService.upload(filePart, logoMaxSizeInKb.get(), false);

        return uploadAssetMono
                .flatMap(uploadedAsset -> {
                    Organization organization = new Organization();
                    final String prevAssetId = organization.getLogoAssetId();
                    organization.setLogoAssetId(uploadedAsset.getId());

                    return mongoUpsertHelper.updateById(organization, organizationId)
                            .flatMap(updateResult -> {
                                if (StringUtils.isEmpty(prevAssetId)) {
                                    return Mono.just(updateResult);
                                }
                                return assetService.remove(prevAssetId).thenReturn(updateResult);
                            });
                });
    }

    @Override
    public Mono<Boolean> deleteLogo(String organizationId) {
        return repository.findByIdAndState(organizationId, ACTIVE)
                .flatMap(organization -> {
                    // delete from asset repo.
                    final String prevAssetId = organization.getLogoAssetId();
                    if (StringUtils.isBlank(prevAssetId)) {
                        return Mono.error(new BizException(BizError.NO_RESOURCE_FOUND, "ASSET_NOT_FOUND", ""));
                    }
                    return assetRepository.findById(prevAssetId)
                            .switchIfEmpty(Mono.error(new BizException(BizError.NO_RESOURCE_FOUND, "ASSET_NOT_FOUND", prevAssetId)))
                            .flatMap(asset -> assetRepository.delete(asset));
                })
                .then(Mono.defer(() -> {
                    // update org.
                    Organization organization = new Organization();
                    organization.setLogoAssetId(null);
                    return mongoUpsertHelper.updateById(organization, organizationId);
                }));
    }

    @Override
    public Mono<Boolean> update(String orgId, Organization updateOrg) {
        return mongoUpsertHelper.updateById(updateOrg, orgId);
    }

    @Override
    public Mono<Boolean> delete(String orgId) {
        Organization organization = new Organization();
        organization.setState(OrganizationState.DELETED);
        return mongoUpsertHelper.updateById(organization, orgId)
                .delayUntil(success -> {
                    if (Boolean.TRUE.equals(success)) {
                        return sendOrgDeletedEvent(orgId);
                    }
                    return Mono.empty();
                });
    }

    private Mono<Void> sendOrgDeletedEvent(String orgId) {
        OrgDeletedEvent event = new OrgDeletedEvent();
        event.setOrgId(orgId);
        applicationContext.publishEvent(event);
        return Mono.empty();
    }

    @Override
    public Mono<Organization> getBySourceAndTpCompanyId(String source, String companyId) {
        return repository.findBySourceAndThirdPartyCompanyIdAndState(source, companyId, ACTIVE);
    }

    @Override
    public Mono<Organization> getByDomain(String domain) {
        return repository.findByOrganizationDomain_DomainAndState(domain, ACTIVE);
    }

    @Override
    public Mono<Boolean> updateCommonSettings(String orgId, String key, Object value) {
        long updateTime = System.currentTimeMillis();
        Update update = Update.update(fieldName(QOrganization.organization.commonSettings) + "." + key, value)
                .set(fieldName(QOrganization.organization.commonSettings) + "." + buildCommonSettingsUpdateTimeKey(key), updateTime);
        return mongoUpsertHelper.upsert(update, FieldName.ID, orgId, Organization.class);
    }

    private String buildCommonSettingsUpdateTimeKey(String key) {
        return key + "_updateTime";
    }
}
