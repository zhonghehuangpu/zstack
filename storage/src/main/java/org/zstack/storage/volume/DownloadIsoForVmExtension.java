package org.zstack.storage.volume;

import org.springframework.beans.factory.annotation.Autowire;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.zstack.core.asyncbatch.While;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.cloudbus.CloudBusCallBack;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.header.core.Completion;
import org.zstack.header.core.NoErrorCompletion;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.image.ImageBackupStorageRefInventory;
import org.zstack.header.image.ImageInventory;
import org.zstack.header.image.ImageVO;
import org.zstack.header.message.MessageReply;
import org.zstack.header.storage.primary.*;
import org.zstack.header.vm.PreVmInstantiateResourceExtensionPoint;
import org.zstack.header.vm.VmInstanceConstant.VmOperation;
import org.zstack.header.vm.VmInstanceSpec;
import org.zstack.header.vm.VmInstanceSpec.ImageSpec;
import org.zstack.header.vm.VmInstanceSpec.IsoSpec;
import org.zstack.header.vm.VmInstanceSpec.VolumeSpec;
import org.zstack.header.vm.VmInstantiateResourceException;
import org.zstack.utils.CollectionUtils;
import org.zstack.utils.Utils;
import org.zstack.utils.function.Function;
import org.zstack.utils.logging.CLogger;

import java.util.ArrayList;
import java.util.List;

import static org.zstack.core.Platform.operr;
import static org.zstack.utils.CollectionDSL.list;

/**
 * Created by frank on 5/23/2015.
 */
@Configurable(preConstruction = true, autowire = Autowire.BY_TYPE)
public class DownloadIsoForVmExtension implements PreVmInstantiateResourceExtensionPoint {
    private CLogger logger = Utils.getLogger(DownloadIsoForVmExtension.class);

    @Autowired
    private CloudBus bus;
    @Autowired
    private DatabaseFacade dbf;

    private static List<VmOperation> operations;
    static {
        operations = list(
                VmOperation.NewCreate,
                VmOperation.Start,
                VmOperation.Reboot
        );
    }

    @Override
    public void preBeforeInstantiateVmResource(VmInstanceSpec spec) throws VmInstantiateResourceException {
    }

    @Override
    public void preInstantiateVmResource(final VmInstanceSpec spec, final Completion completion) {
        if (spec.getDestIsoList().isEmpty() || !operations.contains(spec.getCurrentVmOperation())) {
            completion.success();
            return;
        }

        spec.getDestIsoList().forEach(isoSpec -> {
            assert isoSpec.getBackupStorageUuid() != null : "backup storage uuid cannot be null";
        });

        List<DownloadIsoToPrimaryStorageMsg> msgs = CollectionUtils.transformToList(spec.getDestIsoList(),
             new Function<DownloadIsoToPrimaryStorageMsg,IsoSpec>() {
                @Override
                public DownloadIsoToPrimaryStorageMsg call(IsoSpec isoSpec) {
                    final String psUuid;
                    ImageSpec imageSpec = new ImageSpec();
                    final ImageInventory iso = ImageInventory.valueOf(dbf.findByUuid(isoSpec.getImageUuid(), ImageVO.class));
                    imageSpec.setInventory(iso);
                    imageSpec.setSelectedBackupStorage(CollectionUtils.find(iso.getBackupStorageRefs(), new Function<ImageBackupStorageRefInventory, ImageBackupStorageRefInventory>() {
                        @Override
                        public ImageBackupStorageRefInventory call(ImageBackupStorageRefInventory arg) {
                            return arg.getBackupStorageUuid().equals(isoSpec.getBackupStorageUuid()) ? arg : null;
                        }
                    }));

                    if (VmOperation.NewCreate == spec.getCurrentVmOperation()) {
                        VolumeSpec vspec = spec.getVolumeSpecs().get(0);
                        PrimaryStorageInventory pinv = vspec.getPrimaryStorageInventory();
                        psUuid = pinv.getUuid();
                    } else {
                        psUuid = spec.getVmInventory().getRootVolume().getPrimaryStorageUuid();
                    }

                    DownloadIsoToPrimaryStorageMsg msg = new DownloadIsoToPrimaryStorageMsg();
                    msg.setPrimaryStorageUuid(psUuid);
                    msg.setIsoSpec(imageSpec);
                    msg.setVmInstanceUuid(spec.getVmInventory().getUuid());
                    msg.setDestHostUuid(spec.getDestHost().getUuid());
                    bus.makeTargetServiceIdByResourceUuid(msg, PrimaryStorageConstant.SERVICE_ID, psUuid);
                    return msg;
                }
             }
        );

        List<ErrorCode> errorCodes = new ArrayList<>();
        new While<>(msgs).all((msg, whileCompletion) -> {
            bus.send(msg, new CloudBusCallBack(completion) {
                @Override
                public void run(MessageReply reply) {
                    if (reply.isSuccess()) {
                        DownloadIsoToPrimaryStorageReply re = reply.castReply();
                        VmInstanceSpec.IsoSpec isoSpec = spec.getDestIsoList().stream()
                                .filter(s -> s.getImageUuid().equals(msg.getIsoSpec().getInventory().getUuid()))
                                .findAny()
                                .get();
                        isoSpec.setInstallPath(re.getInstallPath());
                        isoSpec.setPrimaryStorageUuid(msg.getPrimaryStorageUuid());
                    } else {
                        errorCodes.add(reply.getError());
                    }
                    whileCompletion.done();
                }
            });
        }).run(new NoErrorCompletion(completion) {
            @Override
            public void done() {
                if (errorCodes.isEmpty()) {
                    completion.success();
                    return;
                }

                ErrorCode ec = operr("unable to download iso to primary storage, becasue: %s",
                        errorCodes.get(0).getDetails()).causedBy(errorCodes);

                completion.fail(ec);
            }
        });
    }

    @Override
    public void preReleaseVmResource(VmInstanceSpec spec, final Completion completion) {
        List<IsoSpec> isoSpecs = spec.getDestIsoList();

        if (isoSpecs.isEmpty()) {
            completion.success();
            return;
        }

        List<DeleteIsoFromPrimaryStorageMsg> msgs = CollectionUtils.transformToList(isoSpecs,
                new Function<DeleteIsoFromPrimaryStorageMsg, IsoSpec>() {
                    @Override
                    public DeleteIsoFromPrimaryStorageMsg call(IsoSpec arg) {
                        String psUuid;
                        if (VmOperation.NewCreate == spec.getCurrentVmOperation()) {
                            VolumeSpec vspec = spec.getVolumeSpecs().get(0);
                            PrimaryStorageInventory pinv = vspec.getPrimaryStorageInventory();
                            psUuid = pinv.getUuid();
                        } else {
                            psUuid = spec.getVmInventory().getRootVolume().getPrimaryStorageUuid();
                        }

                        DeleteIsoFromPrimaryStorageMsg msg = new DeleteIsoFromPrimaryStorageMsg();
                        msg.setVmInstanceUuid(spec.getVmInventory().getUuid());
                        msg.setIsoSpec(spec.getImageSpec());
                        msg.setPrimaryStorageUuid(psUuid);
                        bus.makeTargetServiceIdByResourceUuid(msg, PrimaryStorageConstant.SERVICE_ID, psUuid);
                        return msg;
                    }
                }
        );

        new While<>(msgs).all((msg, whileCompletion) -> {
            bus.send(msg, new CloudBusCallBack(completion) {
                @Override
                public void run(MessageReply reply) {
                    if (!reply.isSuccess()) {
                        logger.warn(reply.getError().toString());
                    }
                    whileCompletion.done();
                }
            });
        }).run(new NoErrorCompletion(completion) {
            @Override
            public void done() {
                completion.success();
            }
        });
    }
}
